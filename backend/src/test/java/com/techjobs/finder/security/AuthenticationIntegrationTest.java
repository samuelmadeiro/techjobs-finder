package com.techjobs.finder.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techjobs.finder.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cadeia de segurança inteira, contra a aplicação real e o Postgres real.
 *
 * <p>Aqui não há filtro desligado nem identidade injetada: o cookie é o que o servidor
 * emitiu, e cada requisição passa por onde uma requisição de verdade passa. É o único lugar
 * onde faz sentido afirmar "sem sessão não entra" e "a sessão de um não abre o recurso do
 * outro" — as duas coisas dependem justamente do que os testes de fatia desligam.
 */
@AutoConfigureMockMvc
class AuthenticationIntegrationTest extends PostgresIntegrationTest {

    private static final byte[] PDF_BYTES =
            ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                    .getBytes(StandardCharsets.US_ASCII);

    private static final String COOKIE = "tjf_session";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM app_user");
    }

    // ------------------------------------------------------------------ autenticação

    @Test
    @DisplayName("sem sessão, o currículo responde 401 no envelope da API")
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/resumes/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Autenticação necessária."));
    }

    @Test
    @DisplayName("busca de vagas continua pública")
    void searchStaysPublic() throws Exception {
        mockMvc.perform(get("/api/jobs?size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("recomendação exige sessão: ela lê o currículo de alguém")
    void recommendationRequiresSession() throws Exception {
        mockMvc.perform(get("/api/jobs/recommended"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sessão anônima entrega cookie HttpOnly e nenhum token no corpo")
    void anonymousSessionIssuesHttpOnlyCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.anonymous").value(true))
                // O corpo não pode conter credencial: é isso que separa este desenho do
                // token em localStorage que existia antes.
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getValue()).isNotBlank();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("SameSite=Lax");

        // O banco guarda o hash, nunca o token que o navegador recebeu.
        Integer withRawToken = jdbc.queryForObject(
                "SELECT count(*) FROM user_session WHERE token_hash = ?", Integer.class,
                cookie.getValue());
        assertThat(withRawToken).isZero();
    }

    @Test
    @DisplayName("com o cookie, a identidade da sessão é reconhecida")
    void sessionCookieAuthenticates() throws Exception {
        Cookie session = anonymousSession();

        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.anonymous").value(true));
    }

    @Test
    @DisplayName("cookie desconhecido não autentica")
    void unknownCookieIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me").cookie(new Cookie(COOKIE, "nao-existe")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sessão vencida deixa de valer, mesmo com o cookie certo")
    void expiredSessionIsRejected() throws Exception {
        Cookie session = anonymousSession();
        jdbc.update("UPDATE user_session SET expires_at = NOW() - INTERVAL '1 minute'");

        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sessão parada além do limite de inatividade deixa de valer")
    void idleSessionIsRejected() throws Exception {
        Cookie session = anonymousSession();
        jdbc.update("UPDATE user_session SET last_seen_at = NOW() - INTERVAL '30 days'");

        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout revoga a sessão: o mesmo cookie para de funcionar")
    void logoutRevokesSession() throws Exception {
        Cookie session = anonymousSession();

        mockMvc.perform(delete("/api/auth/sessions/current").cookie(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ credenciais

    @Test
    @DisplayName("cadastro mantém a mesma conta e devolve sessão nova")
    void registrationKeepsTheAccount() throws Exception {
        Cookie anonymous = anonymousSession();
        Long userId = userIdOf(anonymous);

        MvcResult result = mockMvc.perform(post("/api/auth/users")
                        .cookie(anonymous)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@exemplo.com\",\"password\":\"senha-bem-longa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.anonymous").value(false))
                .andExpect(jsonPath("$.data.email").value("ana@exemplo.com"))
                .andReturn();

        // Mesma conta: o currículo já enviado continua sendo dela.
        assertThat(result.getResponse().getContentAsString()).contains("\"userId\":" + userId);

        // Token novo: o anterior foi revogado junto com a mudança de nível de acesso.
        Cookie renewed = result.getResponse().getCookie(COOKIE);
        assertThat(renewed).isNotNull();
        assertThat(renewed.getValue()).isNotEqualTo(anonymous.getValue());
        mockMvc.perform(get("/api/auth/me").cookie(anonymous)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(renewed)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("login com senha errada responde 401 sem dizer o que falhou")
    void loginWithWrongPasswordFails() throws Exception {
        register("bruno@exemplo.com", "senha-bem-longa");

        mockMvc.perform(post("/api/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bruno@exemplo.com\",\"password\":\"senha-errada\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("E-mail ou senha inválidos."));
    }

    @Test
    @DisplayName("e-mail inexistente devolve exatamente a mesma resposta")
    void loginWithUnknownEmailFails() throws Exception {
        mockMvc.perform(post("/api/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ninguem@exemplo.com\",\"password\":\"senha-bem-longa\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("E-mail ou senha inválidos."));
    }

    @Test
    @DisplayName("login com a senha certa abre sessão")
    void loginSucceeds() throws Exception {
        register("carla@exemplo.com", "senha-bem-longa");

        mockMvc.perform(post("/api/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carla@exemplo.com\",\"password\":\"senha-bem-longa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.anonymous").value(false));
    }

    @Test
    @DisplayName("senha curta é recusada na validação, com o campo apontado")
    void shortPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dora@exemplo.com\",\"password\":\"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    // ------------------------------------------------------------------ autorização

    @Test
    @DisplayName("a sessão de um usuário não abre o currículo de outro")
    void resumeOfAnotherUserIsNotReachable() throws Exception {
        Cookie owner = anonymousSession();
        Long resumeId = uploadResume(owner);

        Cookie intruder = anonymousSession();

        // 404 e não 403: confirmar que o recurso existe já contaria algo sobre o dono.
        mockMvc.perform(get("/api/resumes/" + resumeId).cookie(intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/resumes/" + resumeId).cookie(intruder))
                .andExpect(status().isNotFound());

        // E o dono continua acessando normalmente.
        mockMvc.perform(get("/api/resumes/" + resumeId).cookie(owner))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("trocar o id na URL não muda de quem é a requisição")
    void identityDoesNotComeFromThePath() throws Exception {
        Cookie owner = anonymousSession();
        uploadResume(owner);
        Cookie intruder = anonymousSession();

        // O invasor pede /me e recebe o dele (nenhum), não o do outro.
        mockMvc.perform(get("/api/resumes/me").cookie(intruder))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ apoio

    private Cookie anonymousSession() throws Exception {
        return mockMvc.perform(post("/api/auth/sessions"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getCookie(COOKIE);
    }

    private void register(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isCreated());
    }

    private Long uploadResume(Cookie session) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/resumes")
                        .file(new MockMultipartFile("file", "curriculo.pdf", "application/pdf",
                                PDF_BYTES))
                        .cookie(session))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        return Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
    }

    private Long userIdOf(Cookie session) throws Exception {
        String body = mockMvc.perform(get("/api/auth/me").cookie(session))
                .andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"userId\":") + "\"userId\":".length();
        int end = body.indexOf(',', start);
        return Long.valueOf(body.substring(start, end).trim());
    }
}
