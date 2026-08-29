package com.techjobs.finder.scraper;

import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executa os scrapers em paralelo isolando falhas: uma fonte que quebra vira um
 * {@link ScrapeResult} de erro, e as demais continuam contribuindo com resultados.
 */
@Service
public class ScraperOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ScraperOrchestrator.class);

    private final List<JobScraper> scrapers;
    private final ScraperProperties properties;

    public ScraperOrchestrator(List<JobScraper> scrapers, ScraperProperties properties) {
        this.scrapers = scrapers;
        this.properties = properties;
    }

    public List<JobScraper> activeScrapers(JobSearchFilter filter) {
        return scrapers.stream()
                .filter(JobScraper::isEnabled)
                .filter(scraper -> properties.isSourceEnabled(scraper.getSource()))
                .filter(scraper -> filter.sources().isEmpty() || filter.sources().contains(scraper.getSource()))
                .toList();
    }

    public List<JobScraper> allScrapers() {
        return List.copyOf(scrapers);
    }

    /** Como as vagas são obtidas: busca do usuário ou varredura profunda agendada. */
    public enum Mode {
        SEARCH,
        HARVEST
    }

    /**
     * @param budget tempo máximo total; scrapers que estourarem são cancelados e
     *               reportados como falha, sem afetar quem já respondeu.
     */
    public List<ScrapeResult> collect(JobSearchFilter filter, Duration budget) {
        return collect(filter, budget, Mode.SEARCH);
    }

    /** Varredura profunda: pagina o máximo permitido em cada fonte, sem filtro do usuário. */
    public List<ScrapeResult> harvest(Duration budget) {
        return collect(JobSearchFilter.empty(), budget, Mode.HARVEST);
    }

    public List<ScrapeResult> collect(JobSearchFilter filter, Duration budget, Mode mode) {
        if (!properties.isEnabled()) {
            log.info("Scraping desabilitado por configuração; nenhuma fonte será consultada");
            return List.of();
        }
        List<JobScraper> active = activeScrapers(filter);
        if (active.isEmpty()) {
            return List.of();
        }

        long startedAt = System.nanoTime();
        log.info("Iniciando coleta ({}) em {} fonte(s) {} para filtro {}",
                mode, active.size(), active.stream().map(JobScraper::getSource).toList(),
                filter.fingerprint());

        List<ScrapeResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, Math.min(properties.getParallelism(), active.size())))) {

            List<Future<ScrapeResult>> futures = active.stream()
                    .map(scraper -> executor.submit(() -> runOne(scraper, filter, mode)))
                    .toList();

            long deadline = System.nanoTime() + budget.toNanos();
            for (int i = 0; i < futures.size(); i++) {
                JobScraper scraper = active.get(i);
                Future<ScrapeResult> future = futures.get(i);
                long remaining = deadline - System.nanoTime();
                try {
                    results.add(future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    future.cancel(true);
                    log.warn("Fonte {} estourou o orçamento de tempo de {}", scraper.getSource(), budget);
                    results.add(ScrapeResult.failure(scraper.getSource(),
                            "Tempo limite excedido", budget));
                } catch (ExecutionException e) {
                    results.add(ScrapeResult.failure(scraper.getSource(),
                            String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()),
                            Duration.ZERO));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ScrapeResult.failure(scraper.getSource(), "Coleta interrompida", Duration.ZERO));
                }
            }
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        long ok = results.stream().filter(ScrapeResult::success).count();
        int total = results.stream().mapToInt(r -> r.jobs().size()).sum();
        log.info("Coleta concluída em {} ms: {}/{} fontes com sucesso, {} vagas cruas",
                elapsed.toMillis(), ok, results.size(), total);
        return results;
    }

    private ScrapeResult runOne(JobScraper scraper, JobSearchFilter filter, Mode mode) {
        long startedAt = System.nanoTime();
        String source = scraper.getSource();
        int limit = mode == Mode.HARVEST
                ? properties.getHarvest().getMaxResultsPerSource()
                : properties.getMaxResultsPerSource();
        try {
            List<RawJob> collected = mode == Mode.HARVEST ? scraper.harvest() : scraper.search(filter);
            List<RawJob> jobs = collected.stream()
                    .filter(RawJob::isUsable)
                    .limit(limit)
                    .map(job -> job.setSourceCode(source))
                    .toList();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            log.info("Fonte {} retornou {} vaga(s) em {} ms", source, jobs.size(), elapsed.toMillis());
            return ScrapeResult.success(source, jobs, elapsed);
        } catch (RuntimeException e) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            // Falha isolada: registra e segue. Nunca propaga para as outras fontes.
            log.error("Fonte {} falhou após {} ms: {}", source, elapsed.toMillis(), e.getMessage(), e);
            return ScrapeResult.failure(source, e.getMessage(), elapsed);
        }
    }
}
