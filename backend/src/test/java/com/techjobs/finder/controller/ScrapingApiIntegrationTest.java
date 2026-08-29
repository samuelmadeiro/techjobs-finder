package com.techjobs.finder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * As duas rotas de coleta, pela cadeia HTTP inteira: filtro de sessão, autorização,
 * serialização e envelope de erro.
 *
 * <p>O orquestrador entra como mock com um propósito específico: provar que <em>nenhuma</em>
 * requisição HTTP o aciona. É a afirmação central da fase — POST enfileira, GET consulta,
 * ninguém coleta dentro do ciclo de requisição.
 */
@AutoConfigureMockMvc
class ScrapingApiIntegrationTest extends PostgresIntegrationTest {

    private static final String COOKIE = "tjf_session";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ScraperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM scraping_job");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM search_cache_entry");
    }

    private Cookie anonymousSession() throws Exception {
        return mockMvc.perform(post("/api/auth/sessions"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getCookie(COOKIE);
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    // ------------------------------------------------------------------ POST

    @Test
    @DisplayName("POST /api/scraping responde 202 com jobId, status e createdAt")
    void postReturnsAccepted() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/scraping")
                        .cookie(anonymousSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"java\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        JsonNode data = dataOf(result);
        assertThat(data.get("id").asText()).isNotBlank();
        assertThat(data.get("createdAt").asText()).isNotBlank();
        assertThat(data.get("attemptCount").asInt()).isZero();
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/scraping/" + data.get("id").asText());
    }

    @Test
    @DisplayName("POST não executa scraping: nenhuma fonte é acionada na requisição")
    void postDoesNotScrapeInline() throws Exception {
        mockMvc.perform(post("/api/scraping")
                        .cookie(anonymousSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"kotlin\"}"))
                .andExpect(status().isAccepted());

        // O caminho POST → scraping → resposta não existe.
        verifyNoInteractions(orchestrator);
        assertThat(jdbc.queryForObject("SELECT status FROM scraping_job", String.class))
                .isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("POST sem corpo enfileira a coleta do feed geral")
    void postWithoutBodyIsAccepted() throws Exception {
        mockMvc.perform(post("/api/scraping").cookie(anonymousSession()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    @DisplayName("pedidos repetidos do mesmo filtro apontam para a mesma execução")
    void repeatedPostsShareTheSameJob() throws Exception {
        Cookie session = anonymousSession();
        String first = dataOf(mockMvc.perform(post("/api/scraping").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"java\"}"))
                .andExpect(status().isAccepted()).andReturn()).get("id").asText();

        String second = dataOf(mockMvc.perform(post("/api/scraping").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"java\"}"))
                .andExpect(status().isAccepted()).andReturn()).get("id").asText();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scraping_job", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("sem sessão não se pede coleta")
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/scraping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Autenticação necessária."));
    }

    // ------------------------------------------------------------------ GET

    @Test
    @DisplayName("GET /api/scraping/{id} devolve o estado da coleta")
    void getReturnsJobState() throws Exception {
        Cookie session = anonymousSession();
        String id = dataOf(mockMvc.perform(post("/api/scraping").cookie(session))
                .andReturn()).get("id").asText();

        MvcResult result = mockMvc.perform(get("/api/scraping/" + id).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        JsonNode data = dataOf(result);
        assertThat(data.has("createdAt")).isTrue();
        assertThat(data.has("attemptCount")).isTrue();
        // Nada de infraestrutura sai daqui.
        assertThat(data.has("workerId")).isFalse();
        assertThat(data.has("leaseUntil")).isFalse();
        assertThat(data.has("filterJson")).isFalse();
        assertThat(data.has("nextAttemptAt")).isFalse();
    }

    @Test
    @DisplayName("um usuário não consulta a coleta de outro")
    void otherUsersJobIsNotVisible() throws Exception {
        Cookie alice = anonymousSession();
        String aliceJob = dataOf(mockMvc.perform(post("/api/scraping").cookie(alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"java\"}"))
                .andReturn()).get("id").asText();

        Cookie bob = anonymousSession();

        // Mesma resposta que um id inexistente: não se confirma sequer que o job existe.
        mockMvc.perform(get("/api/scraping/" + aliceJob).cookie(bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("id inexistente responde igual a id de outro dono")
    void unknownJobIsNotFound() throws Exception {
        mockMvc.perform(get("/api/scraping/" + UUID.randomUUID()).cookie(anonymousSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("job interno do scheduler não pertence a nenhum usuário")
    void schedulerJobHasNoOwner() throws Exception {
        Cookie session = anonymousSession();
        String id = dataOf(mockMvc.perform(post("/api/scraping").cookie(session))
                .andReturn()).get("id").asText();
        // Um job do scheduler é exatamente isto: sem dono.
        jdbc.update("UPDATE scraping_job SET requested_by_user_id = NULL WHERE id = ?::uuid", id);

        mockMvc.perform(get("/api/scraping/" + id).cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("consulta exige sessão")
    void getRequiresSession() throws Exception {
        mockMvc.perform(get("/api/scraping/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ busca de vagas

    @Test
    @DisplayName("GET /api/jobs continua respondendo sem acionar scraper nenhum")
    void jobSearchNeverScrapes() throws Exception {
        mockMvc.perform(get("/api/jobs?size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/jobs?keyword=java&size=5&refresh=true"))
                .andExpect(status().isOk());

        // Nem a busca comum nem o refresh forçado tocam a fonte externa dentro da
        // requisição: o que a busca faz é, no máximo, enfileirar trabalho.
        verifyNoInteractions(orchestrator);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scraping_job WHERE status = 'QUEUED'",
                Integer.class)).isPositive();
    }

    @Test
    @DisplayName("cem buscas do mesmo filtro produzem uma execução, não cem")
    void repeatedSearchesProduceOneJob() throws Exception {
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/jobs?keyword=java&size=5&refresh=true"))
                    .andExpect(status().isOk());
        }

        verifyNoInteractions(orchestrator);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE status IN ('QUEUED','RUNNING')",
                Integer.class)).isEqualTo(1);
    }
}
