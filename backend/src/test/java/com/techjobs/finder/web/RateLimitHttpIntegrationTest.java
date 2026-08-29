package com.techjobs.finder.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O limite visto pelo cliente HTTP, com o contador real no Postgres.
 *
 * <p>Capacidade 2 para o cenário caber em três chamadas. O que se verifica aqui é o
 * contrato — 429 com Retry-After no envelope da API — e que a chave é a conta autenticada:
 * o limite de um usuário não afeta o outro.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "techjobs.rate-limit.resume-upload.capacity=2",
        "techjobs.rate-limit.resume-upload.period=1h"
})
class RateLimitHttpIntegrationTest extends PostgresIntegrationTest {

    private static final byte[] PDF_BYTES =
            ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                    .getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rate_limit_bucket");
        jdbc.update("DELETE FROM app_user");
    }

    private Cookie session() throws Exception {
        return mockMvc.perform(post("/api/auth/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getCookie("tjf_session");
    }

    private void upload(Cookie session, int expectedStatus) throws Exception {
        mockMvc.perform(multipart("/api/resumes")
                        .file(new MockMultipartFile("file", "curriculo.pdf", "application/pdf", PDF_BYTES))
                        .cookie(session))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("passar do teto responde 429 com Retry-After")
    void tooManyUploadsReturns429() throws Exception {
        Cookie session = session();

        upload(session, 201);
        upload(session, 201);

        mockMvc.perform(multipart("/api/resumes")
                        .file(new MockMultipartFile("file", "curriculo.pdf", "application/pdf", PDF_BYTES))
                        .cookie(session))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("o limite acompanha a conta, não a máquina")
    void limitIsPerAccount() throws Exception {
        Cookie primeira = session();
        upload(primeira, 201);
        upload(primeira, 201);
        upload(primeira, 429);

        // Mesma origem, conta diferente: o balde é outro.
        Cookie segunda = session();
        upload(segunda, 201);
    }
}
