package com.techjobs.finder.service;

import com.techjobs.finder.config.SearchProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.entity.ScrapingJobStatus;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ponte entre a busca e a fila de scraping.
 *
 * <p>Continua valendo o que já valia: a requisição responde com o que o banco tem e nunca
 * espera coleta. O que mudou é onde o trabalho fica enquanto espera. Antes era um
 * {@code Runnable} num {@code ThreadPoolExecutor} com fila de oito e descarte silencioso —
 * reinício perdia o trabalho, falha não gerava nova tentativa, e não havia como perguntar o
 * que estava acontecendo. Agora vira uma linha em {@code scraping_job}, e quem executa é o
 * {@link ScrapingJobWorker}, que pode estar em outra instância.
 *
 * <p>Duas defesas contra a debandada, e elas não se sobrepõem:
 *
 * <ul>
 *   <li>{@link SearchCacheService#tryClaimRefresh} continua sendo o <strong>freio por
 *       tempo</strong>: reserva a combinação marcando {@code executed_at} antes de qualquer
 *       coleta, então uma coleta que falha não vira martelada em sequência contra a fonte.</li>
 *   <li>O índice único parcial de {@code scraping_job} é o freio por <strong>duplicação de
 *       execução</strong>: mesmo que dois pedidos passem pela reserva, existe um job ativo
 *       só.</li>
 * </ul>
 */
@Service
public class SearchRefreshService {

    private static final Logger log = LoggerFactory.getLogger(SearchRefreshService.class);

    private final SearchCacheService cacheService;
    private final ScrapingJobService jobService;
    private final ScrapingJobWorker worker;
    private final SearchProperties properties;

    /** Contadores de diagnóstico, lidos por métricas e testes. Não afetam decisão nenhuma. */
    private final AtomicLong scheduled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong skippedByClaim = new AtomicLong();

    public SearchRefreshService(SearchCacheService cacheService,
                                ScrapingJobService jobService,
                                ScrapingJobWorker worker,
                                SearchProperties properties) {
        this.cacheService = cacheService;
        this.jobService = jobService;
        this.worker = worker;
        this.properties = properties;
    }

    /**
     * Garante que esta combinação de filtros esteja na fila de coleta.
     *
     * <p>Retorna imediatamente: o único trabalho feito aqui é um upsert condicional e, quando
     * ele é ganho, um insert. Nada de rede, nada de scraping.
     *
     * @return {@code true} quando esta chamada colocou um job novo na fila
     */
    public boolean requestRefresh(JobSearchFilter filter, boolean forced) {
        if (!cacheService.tryClaimRefresh(filter, forced)) {
            skippedByClaim.incrementAndGet();
            log.debug("Coleta do filtro {} já assumida por outro; nada a enfileirar",
                    filter.fingerprint());
            return false;
        }

        ScrapingJobService.Enqueued enqueued = jobService.enqueue(filter, ScrapingJob.Mode.SEARCH,
                properties.getOnDemandBudget(), null);
        if (!enqueued.created()) {
            // Já havia execução ativa para o mesmo fingerprint: o trabalho está coberto.
            rejected.incrementAndGet();
            return false;
        }
        scheduled.incrementAndGet();
        return true;
    }

    /** Instantâneo para métricas e testes. */
    public record RefreshStats(long scheduled, long skippedByClaim, long rejected, long completed) {
    }

    public RefreshStats stats() {
        // "completed" agora sai do banco: com várias instâncias, o número que interessa é o
        // da fila inteira, não o desta JVM.
        return new RefreshStats(scheduled.get(), skippedByClaim.get(), rejected.get(),
                jobService.count(ScrapingJobStatus.COMPLETED));
    }

    /**
     * Espera as coletas em andamento terminarem.
     *
     * <p>Só enxerga o worker desta instância — que é o suficiente para teste e desligamento
     * ordenado. Estado global de fila se lê no banco, não aqui.
     */
    public boolean awaitQuiet(Duration timeout) throws InterruptedException {
        return worker.awaitIdle(timeout);
    }
}
