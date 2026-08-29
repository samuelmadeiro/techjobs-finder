package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.repository.CompanyRepository;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Ingestão concorrente: o scheduler e uma busca sob demanda podem coletar a mesma vaga ao
 * mesmo tempo. A segunda inserção esbarra na constraint única de {@code job.fingerprint}, e
 * o que este teste garante é que esse encontro não custa mais do que a própria vaga —
 * nenhum lote é perdido e o banco não fica inconsistente.
 */
class JobIngestionConcurrencyTest extends PostgresIntegrationTest {

    private static final int SHARED = 120;
    private static final int EXCLUSIVE = 80;

    @Autowired
    private JobIngestionService ingestionService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TechnologySeedService technologySeedService;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        companyRepository.deleteAll();
        technologySeedService.seed();
    }

    @Test
    @DisplayName("dois lotes com vagas em comum entram sem perda e sem duplicar")
    void concurrentIngestionKeepsEveryJobExactlyOnce() throws Exception {
        // Um lote imita o scheduler, o outro a busca sob demanda; a faixa "shared" é a
        // mesma vaga chegando pelos dois caminhos ao mesmo tempo.
        List<ScrapeResult> scheduled = batch("remoteok", 0, SHARED + EXCLUSIVE);
        List<ScrapeResult> onDemand = batch("arbeitnow", 0, SHARED);
        onDemand = List.of(merge(onDemand.get(0), batch("arbeitnow", SHARED + EXCLUSIVE, EXCLUSIVE)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            List<ScrapeResult> second = onDemand;
            Future<JobIngestionService.IngestionStats> a =
                    pool.submit(() -> ingestAfter(startLine, scheduled));
            Future<JobIngestionService.IngestionStats> b =
                    pool.submit(() -> ingestAfter(startLine, second));

            startLine.countDown();
            JobIngestionService.IngestionStats statsA = a.get(2, TimeUnit.MINUTES);
            JobIngestionService.IngestionStats statsB = b.get(2, TimeUnit.MINUTES);

            // Nenhum candidato some por causa do conflito: ou virou vaga nova, ou
            // atualizou a que o outro lote acabou de inserir.
            assertThat(statsA.created() + statsA.updated()).isEqualTo(statsA.afterDedup());
            assertThat(statsB.created() + statsB.updated()).isEqualTo(statsB.afterDedup());
            assertThat(statsA.skipped() + statsB.skipped()).isZero();
        } finally {
            pool.shutdownNow();
        }

        List<Job> stored = jobRepository.findAll();
        // Faixa compartilhada gravada uma vez só, faixas exclusivas de cada lote inteiras.
        assertThat(stored).hasSize(SHARED + EXCLUSIVE * 2);
        assertThat(stored.stream().map(Job::getFingerprint).distinct().count())
                .isEqualTo(stored.size());
        assertThat(stored).allSatisfy(job -> {
            assertThat(job.getCompany()).isNotNull();
            assertThat(job.getSource()).isNotNull();
            assertThat(job.isActive()).isTrue();
        });
    }

    private JobIngestionService.IngestionStats ingestAfter(CountDownLatch startLine,
                                                           List<ScrapeResult> results)
            throws InterruptedException {
        startLine.await();
        return ingestionService.ingest(results);
    }

    /** Um resultado por fonte, com {@code count} vagas a partir de {@code offset}. */
    private List<ScrapeResult> batch(String source, int offset, int count) {
        List<RawJob> jobs = new ArrayList<>(count);
        for (int i = offset; i < offset + count; i++) {
            jobs.add(raw(source, i));
        }
        return List.of(ScrapeResult.success(source, jobs, Duration.ZERO));
    }

    private ScrapeResult merge(ScrapeResult first, List<ScrapeResult> rest) {
        List<RawJob> jobs = new ArrayList<>(first.jobs());
        rest.forEach(result -> jobs.addAll(result.jobs()));
        return ScrapeResult.success(first.source(), jobs, Duration.ZERO);
    }

    /**
     * Empresa distinta por índice: o fingerprint sai de empresa + título + local, então
     * vagas de índices diferentes nunca colidem e vagas de mesmo índice sempre colidem,
     * mesmo vindo de fontes diferentes.
     */
    private RawJob raw(String source, int index) {
        return new RawJob()
                .setTitle("Desenvolvedor Java Pleno " + index)
                .setCompany("Empresa Concorrente " + index)
                .setLocation("Remoto")
                .setDescriptionHtml("<p>Java, Spring Boot e PostgreSQL</p>")
                .setUrl("https://" + source + ".test/j/" + index)
                .setWorkModelHint("remote")
                .setPublishedAt(Instant.now())
                .setSourceCode(source);
    }
}
