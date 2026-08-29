package com.techjobs.finder.controller;

import com.techjobs.finder.config.SearchProperties;
import com.techjobs.finder.dto.ApiResponse;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.scraping.ScrapingJobResponse;
import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.service.CountryCatalog;
import com.techjobs.finder.service.ScrapingJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pedido manual de coleta e consulta do andamento.
 *
 * <p>O POST não coleta: ele enfileira e devolve 202. É a diferença entre uma requisição que
 * responde em milissegundos e uma que segura o cliente por até vinte segundos esperando seis
 * sites responderem. Quem executa é o worker, possivelmente em outra instância.
 *
 * <p>Autenticação: as duas rotas caem em {@code anyRequest().authenticated()} do
 * {@code SecurityConfig}. Coleta manual é trabalho caro contra terceiros — não é coisa para
 * qualquer visitante disparar —, e a consulta precisa de identidade para ter dono.
 */
@RestController
@RequestMapping("/api/scraping")
@Tag(name = "Coleta")
public class ScrapingController {

    private final ScrapingJobService jobService;
    private final SearchProperties searchProperties;
    private final CountryCatalog countryCatalog;

    public ScrapingController(ScrapingJobService jobService, SearchProperties searchProperties,
                              CountryCatalog countryCatalog) {
        this.jobService = jobService;
        this.searchProperties = searchProperties;
        this.countryCatalog = countryCatalog;
    }

    /**
     * Enfileira uma coleta para os filtros informados.
     *
     * <p>Idempotente do ponto de vista de quem chama: pedir cem vezes o mesmo filtro devolve
     * cem vezes 202, sempre apontando para <em>a mesma</em> execução ativa. Não existe caminho
     * daqui para o scraper.
     *
     * <p>O corpo é opcional; sem ele, a coleta é do feed geral das fontes.
     */
    @Operation(summary = "Enfileira uma coleta e devolve 202 imediatamente",
            description = "Não executa scraping na requisição. Pedidos equivalentes enquanto "
                    + "uma execução estiver ativa devolvem o mesmo job.")
    @PostMapping
    public ResponseEntity<ApiResponse<ScrapingJobResponse>> enqueue(
            @Valid @RequestBody(required = false) JobSearchRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser current) {

        JobSearchRequest effective = request == null ? new JobSearchRequest() : request;
        ScrapingJobService.Enqueued enqueued = jobService.enqueue(
                effective.toFilter(countryCatalog), ScrapingJob.Mode.SEARCH,
                searchProperties.getOnDemandBudget(), current.id());

        ScrapingJobResponse body = ScrapingJobResponse.from(enqueued.job());
        return ResponseEntity.accepted()
                .location(URI.create("/api/scraping/" + body.id()))
                .body(ApiResponse.ok(body, enqueued.created()
                        ? "Coleta enfileirada."
                        : "Já existe uma coleta em andamento para estes filtros."));
    }

    /**
     * Estado da coleta.
     *
     * <p>Só o dono enxerga. Job de outro usuário responde 404, igual a job inexistente:
     * distinguir os dois casos já contaria a um estranho que aquele identificador existe.
     */
    @Operation(summary = "Estado de uma coleta pedida por este usuário")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScrapingJobResponse>> byId(
            @PathVariable UUID id,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser current) {
        return ResponseEntity.ok(
                ApiResponse.ok(ScrapingJobResponse.from(jobService.findOwned(id, current))));
    }
}
