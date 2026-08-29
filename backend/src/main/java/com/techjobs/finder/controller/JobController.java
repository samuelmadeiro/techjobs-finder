package com.techjobs.finder.controller;

import com.techjobs.finder.dto.ApiResponse;
import com.techjobs.finder.dto.PageResponse;
import com.techjobs.finder.dto.job.JobDetailsResponse;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.service.JobSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Busca, detalhe e recomendação de vagas.
 *
 * <p>O controller só recebe, valida e delega: nenhuma regra de negócio mora aqui.
 * A busca é pública: sem sessão, {@code current} chega nulo e a resposta sai sem
 * compatibilidade. Com sessão, o serviço anexa a compatibilidade com o currículo daquela
 * conta — que é encontrada pelo id da sessão, nunca por um parâmetro do cliente.
 */
@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Vagas")
public class JobController {

    private final JobSearchService searchService;

    public JobController(JobSearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(summary = "Lista vagas aplicando os filtros informados")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<JobSummaryResponse>>> list(
            @Valid @ModelAttribute JobSearchRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser current) {
        return ResponseEntity.ok(ApiResponse.ok(searchService.search(request, current)));
    }

    /**
     * Alias de {@code GET /api/jobs}, com o mesmo contrato e a mesma implementação.
     *
     * <p><strong>Depreciado.</strong> O endpoint canônico de busca é {@code GET /api/jobs}:
     * é o que o frontend usa e o que a documentação descreve. Este caminho continua no ar
     * porque não há como afirmar que nenhum consumidor externo o chame — a remoção fica
     * para quando isso for verificado, e até lá quem chegar aqui recebe exatamente a mesma
     * resposta de antes.
     *
     * @deprecated use {@code GET /api/jobs}
     */
    @Deprecated(since = "0.2.0")
    @Operation(summary = "Depreciado: use GET /api/jobs",
            description = "Alias histórico de GET /api/jobs, mantido por compatibilidade. "
                    + "Mesmo contrato, mesma resposta. Será removido em versão futura.",
            deprecated = true)
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<JobSummaryResponse>>> search(
            @Valid @ModelAttribute JobSearchRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser current) {
        return ResponseEntity.ok(ApiResponse.ok(searchService.search(request, current)));
    }

    @Operation(summary = "Vagas ordenadas pela compatibilidade com o currículo enviado")
    @GetMapping("/recommended")
    public ResponseEntity<ApiResponse<PageResponse<JobSummaryResponse>>> recommended(
            @Valid @ModelAttribute JobSearchRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser current) {
        return ResponseEntity.ok(ApiResponse.ok(searchService.recommend(request, current)));
    }

    @Operation(summary = "Detalhe completo de uma vaga, com descrição e requisitos")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JobDetailsResponse>> byId(
            @PathVariable Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser current) {
        return ResponseEntity.ok(ApiResponse.ok(searchService.findById(id, current)));
    }
}
