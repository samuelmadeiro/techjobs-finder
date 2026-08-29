package com.techjobs.finder.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techjobs.finder.dto.PageResponse;
import com.techjobs.finder.dto.job.CompanySummary;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.job.JobSourceSummary;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.dto.job.SearchMeta;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.exception.ResourceNotFoundException;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.service.JobSearchService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contrato HTTP da busca, sem a cadeia de segurança: {@code addFilters = false} desliga os
 * filtros para que este teste fique sobre o controller. Quem autentica é a cadeia, e ela
 * tem teste próprio contra a aplicação inteira em {@code AuthenticationIntegrationTest} —
 * misturar as duas coisas aqui só tornaria a falha mais difícil de localizar.
 */
@WebMvcTest(JobController.class)
@AutoConfigureMockMvc(addFilters = false)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobSearchService searchService;

    /**
     * O limitador guarda o contador no Postgres, e esta fatia não tem banco. Ele não
     * intercepta nenhuma rota daqui — só precisa existir para o WebConfig ser construído.
     */
    @MockitoBean
    private com.techjobs.finder.web.RateLimiter rateLimiter;

    private JobSummaryResponse sampleJob() {
        return new JobSummaryResponse(1L, "Desenvolvedor Java Júnior",
                CompanySummary.brief(15L, "Empresa XYZ", null), "João Pessoa - PB",
                WorkModel.REMOTE, ExperienceLevel.JUNIOR, 2, List.of("Java"), List.of("Spring Boot"),
                "Resumo da vaga", null, Instant.parse("2026-08-05T10:00:00Z"),
                new JobSourceSummary("remotive", "Remotive", "https://remotive.com"),
                "https://exemplo.com/vaga/1", 100, null);
    }

    @Test
    @DisplayName("GET /api/jobs devolve a página dentro do envelope padrão")
    void listReturnsResults() throws Exception {
        given(searchService.search(any(JobSearchRequest.class), isNull()))
                .willReturn(PageResponse.of(List.of(sampleJob()), 0, 20,
                        new SearchMeta(false, Instant.now(), List.of("remotive"), List.of())));

        mockMvc.perform(get("/api/jobs?language=java&level=JUNIOR&workModel=REMOTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Desenvolvedor Java Júnior"))
                .andExpect(jsonPath("$.data.content[0].company.name").value("Empresa XYZ"))
                .andExpect(jsonPath("$.data.content[0].relevance").value(100))
                .andExpect(jsonPath("$.data.content[0].source.code").value("remotive"))
                .andExpect(jsonPath("$.data.content[0].originalUrl").value("https://exemplo.com/vaga/1"));
    }

    @Test
    @DisplayName("resposta expõe as fontes que falharam sem virar erro HTTP")
    void reportsPartialFailures() throws Exception {
        given(searchService.search(any(JobSearchRequest.class), isNull()))
                .willReturn(PageResponse.of(List.of(sampleJob()), 0, 20,
                        new SearchMeta(false, Instant.now(), List.of("remotive", "remoteok"),
                                List.of(new SearchMeta.SourceFailure("remoteok", "HTTP 503")))));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.meta.failures[0].source").value("remoteok"));
    }

    @Test
    @DisplayName("a identidade da sessão chega ao serviço para o cálculo de compatibilidade")
    void forwardsAuthenticatedUser() throws Exception {
        AuthenticatedUser current = new AuthenticatedUser(7L, 3L, true);
        authenticateAs(current);
        given(searchService.search(any(JobSearchRequest.class), eq(current)))
                .willReturn(PageResponse.of(List.of(sampleJob()), 0, 20,
                        new SearchMeta(false, Instant.now(), List.of(), List.of())));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(1));
    }

    @Test
    @DisplayName("nível inválido retorna 400 com o mesmo envelope")
    void invalidLevelReturnsBadRequest() throws Exception {
        willThrow(new com.techjobs.finder.exception.InvalidFilterException("level", "xpto", "valores aceitos: ..."))
                .given(searchService).search(any(JobSearchRequest.class), isNull());

        mockMvc.perform(get("/api/jobs?level=xpto"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                // O campo problemático vai em 'errors'; 'data' fica nulo como em todo erro.
                .andExpect(jsonPath("$.errors[0].field").value("level"))
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("tamanho de página fora do limite é rejeitado pela validação")
    void rejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/api/jobs?size=500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    @DisplayName("resposta de sucesso não carrega o campo de erros")
    void successHasNoErrorsField() throws Exception {
        given(searchService.search(any(JobSearchRequest.class), isNull()))
                .willReturn(PageResponse.of(List.of(sampleJob()), 0, 20,
                        new SearchMeta(false, Instant.now(), List.of(), List.of())));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    @DisplayName("vaga inexistente retorna 404")
    void missingJobReturnsNotFound() throws Exception {
        given(searchService.findById(eq(99L), isNull()))
                .willThrow(new ResourceNotFoundException("Vaga", 99L));

        mockMvc.perform(get("/api/jobs/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());
    }


    /**
     * Coloca a identidade no contexto de seguranca.
     *
     * <p>Com {@code addFilters = false} nao existe filtro para carregar o contexto a partir
     * da requisicao, e {@code @AuthenticationPrincipal} le exatamente dele. Entao o teste
     * escreve o contexto direto, que e o que o filtro de sessao faria depois de validar o
     * cookie.
     */
    private void authenticateAs(AuthenticatedUser user) {
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearSecurityContext() {
        TestSecurityContextHolder.clearContext();
    }
}
