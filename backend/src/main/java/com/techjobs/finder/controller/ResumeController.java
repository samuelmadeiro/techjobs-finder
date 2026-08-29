package com.techjobs.finder.controller;

import com.techjobs.finder.dto.ApiResponse;
import com.techjobs.finder.dto.resume.ResumeResponse;
import com.techjobs.finder.dto.resume.ResumeUploadResponse;
import com.techjobs.finder.exception.ResourceNotFoundException;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.service.ResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Currículo do usuário.
 *
 * <p>Todas as rotas exigem sessão, e o dono vem sempre de {@code @AuthenticationPrincipal}
 * — nunca do caminho, do corpo ou de um cabeçalho. O {@code id} da URL diz qual recurso se
 * quer, não quem está pedindo.
 *
 * <p>Nenhum endpoint aqui devolve o arquivo enviado. O que sai é o perfil estruturado —
 * o binário só existe no banco, para permitir reprocessamento, e some no {@code DELETE}.
 */
@RestController
@RequestMapping("/api/resumes")
@Tag(name = "Currículo")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @Operation(summary = "Envia um currículo em PDF ou DOCX e devolve o perfil extraído")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ResumeUploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser current) {
        ResumeUploadResponse response = resumeService.upload(file, current);
        URI location = URI.create("/api/resumes/" + response.resume().id());
        return ResponseEntity.created(location)
                .body(ApiResponse.ok(response, "Currículo recebido e analisado."));
    }

    @Operation(summary = "Perfil do currículo mais recente do usuário")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<ResumeResponse>> current(
            @AuthenticationPrincipal AuthenticatedUser current) {
        return resumeService.currentProfile(current)
                .map(profile -> ResponseEntity.ok(ApiResponse.ok(profile)))
                .orElseThrow(() -> new ResourceNotFoundException("Currículo", "atual"));
    }

    @Operation(summary = "Perfil de um currículo específico do próprio usuário")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ResumeResponse>> byId(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser current) {
        return ResponseEntity.ok(ApiResponse.ok(resumeService.byId(id, current)));
    }

    @Operation(summary = "Exclui o currículo e o arquivo original")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser current) {
        resumeService.delete(id, current);
        return ResponseEntity.ok(ApiResponse.ok(null, "Currículo excluído."));
    }
}
