package com.techjobs.finder.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techjobs.finder.dto.resume.ResumeResponse;
import com.techjobs.finder.dto.resume.ResumeUploadResponse;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.ParseStatus;
import com.techjobs.finder.exception.InvalidUploadException;
import com.techjobs.finder.exception.ResourceNotFoundException;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.web.RateLimiter;
import com.techjobs.finder.service.ResumeService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contrato HTTP do currículo: códigos de status, cabeçalhos e envelope.
 *
 * <p>O limite de envios fica com capacidade 2 para o teste de 429 caber em três chamadas,
 * sem espera nem relógio falso.
 */
@WebMvcTest(ResumeController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "techjobs.rate-limit.resume-upload.capacity=2",
        "techjobs.rate-limit.resume-upload.period=1h"
})
class ResumeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResumeService resumeService;

    /**
     * O limitador passou a guardar o contador no Postgres, e esta fatia não tem banco.
     * Aqui ele é dublê e sempre libera: o comportamento HTTP do limite (429 e Retry-After)
     * é verificado contra a aplicação inteira em RateLimitHttpIntegrationTest.
     */
    @MockitoBean
    private RateLimiter rateLimiter;

    private ResumeResponse profile() {
        return new ResumeResponse(7L, "curriculo.pdf", 1024L, "application/pdf",
                Instant.parse("2026-08-01T12:00:00Z"), ParseStatus.PARSED, null,
                "Fulana de Tal", "Desenvolvedora Java", ExperienceLevel.MID, 4, null,
                "João Pessoa - PB", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Cada teste usa um token diferente de propósito: a chave do limitador é o token, e o
     * balde vive no contexto Spring, compartilhado pelos métodos de teste. Tokens distintos
     * dão a cada cenário um balde próprio, sem precisar zerar estado entre testes.
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder upload(
            AuthenticatedUser user) {
        authenticateAs(user);
        return multipart("/api/resumes").file(pdf());
    }

    private static AuthenticatedUser user(long id) {
        return new AuthenticatedUser(id, id, true);
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


    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "curriculo.pdf", "application/pdf",
                "%PDF-1.4 conteudo".getBytes());
    }

    @org.junit.jupiter.api.BeforeEach
    void allowEveryRequest() {
        given(rateLimiter.consume(org.mockito.ArgumentMatchers.anyString()))
                .willReturn(java.util.Optional.empty());
    }

    @Test
    @DisplayName("upload devolve 201 com Location apontando para o currículo criado")
    void uploadReturnsCreatedWithLocation() throws Exception {
        given(resumeService.upload(any(), eq(user(11L))))
                .willReturn(new ResumeUploadResponse(profile()));

        mockMvc.perform(upload(user(11L)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/resumes/7"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resume.id").value(7))
                // O token da sessao nunca vai no corpo: ele vive no cookie HttpOnly.
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("arquivo recusado na validação vira 422 com mensagem exibível")
    void invalidUploadReturnsUnprocessable() throws Exception {
        willThrow(new InvalidUploadException("Formato não aceito. Envie PDF ou DOCX."))
                .given(resumeService).upload(any(), eq(user(12L)));

        mockMvc.perform(upload(user(12L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Formato não aceito. Envie PDF ou DOCX."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("sem currículo para o token, /me responde 404")
    void missingCurrentResumeReturnsNotFound() throws Exception {
        authenticateAs(user(14L));
        given(resumeService.currentProfile(user(14L))).willReturn(Optional.empty());

        mockMvc.perform(get("/api/resumes/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("currículo de outro dono responde 404, não 403")
    void otherOwnersResumeReturnsNotFound() throws Exception {
        // Confirmar a existência de um recurso alheio já seria vazamento de informação.
        authenticateAs(user(15L));
        given(resumeService.byId(eq(7L), eq(user(15L))))
                .willThrow(new ResourceNotFoundException("Currículo", 7L));

        mockMvc.perform(get("/api/resumes/7"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("exclusão responde 200 com o envelope padrão")
    void deleteReturnsOk() throws Exception {
        authenticateAs(user(16L));
        mockMvc.perform(delete("/api/resumes/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Currículo excluído."));
    }
}
