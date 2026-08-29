package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.repository.CompanyRepository;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guarda contra a volta do N+1 na ingestão.
 *
 * <p>A versão anterior consultava o banco duas vezes por candidato — "essa vaga já
 * existe?" e "quais as vagas ativas dessa empresa?" —, então o número de consultas
 * acompanhava o tamanho do lote. Aqui o que se afirma é a forma da curva: as consultas de
 * leitura crescem com o número de sub-lotes, não com o de vagas. Os números medidos vão
 * para o log.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class JobIngestionQueryCountTest extends PostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(JobIngestionQueryCountTest.class);

    private static final int CANDIDATES = 1_000;
    private static final int JOBS_PER_COMPANY = 4;

    @Autowired
    private JobIngestionService ingestionService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TechnologySeedService technologySeedService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        companyRepository.deleteAll();
        technologySeedService.seed();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("consultas de leitura crescem por sub-lote, não por vaga")
    void readQueriesDoNotGrowPerCandidate() {
        List<ScrapeResult> batch = List.of(
                ScrapeResult.success("remoteok", rawJobs(), Duration.ZERO));

        statistics.clear();
        long startedAt = System.nanoTime();
        JobIngestionService.IngestionStats cold = ingestionService.ingest(batch);
        long coldMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        long coldQueries = statistics.getQueryExecutionCount();

        statistics.clear();
        startedAt = System.nanoTime();
        JobIngestionService.IngestionStats warm = ingestionService.ingest(batch);
        long warmMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        long warmQueries = statistics.getQueryExecutionCount();

        int chunks = (int) Math.ceil((double) CANDIDATES / JobIngestionService.CHUNK_SIZE);
        log.info("""
                        Ingestão de {} candidatos em {} sub-lote(s), {} empresa(s)
                          1ª rodada (inserção): {} novas, {} atualizadas, {} consultas, {} ms
                          2ª rodada (reingestão): {} novas, {} atualizadas, {} consultas, {} ms
                          consultas por candidato: {} / {}""",
                CANDIDATES, chunks, CANDIDATES / JOBS_PER_COMPANY,
                cold.created(), cold.updated(), coldQueries, coldMillis,
                warm.created(), warm.updated(), warmQueries, warmMillis,
                String.format("%.3f", (double) coldQueries / CANDIDATES),
                String.format("%.3f", (double) warmQueries / CANDIDATES));

        assertThat(cold.created()).isEqualTo(CANDIDATES);
        assertThat(cold.skipped()).isZero();
        assertThat(warm.updated()).isEqualTo(CANDIDATES);
        assertThat(warm.created()).isZero();

        // Teto generoso e ainda assim uma ordem de grandeza abaixo do "duas por candidato"
        // de antes: o que se quer barrar é qualquer consulta dentro do laço de vagas.
        long ceiling = chunks * 10L;
        assertThat(coldQueries).isLessThanOrEqualTo(ceiling);
        assertThat(warmQueries).isLessThanOrEqualTo(ceiling);
    }

    private List<RawJob> rawJobs() {
        List<RawJob> jobs = new ArrayList<>(CANDIDATES);
        for (int i = 0; i < CANDIDATES; i++) {
            jobs.add(new RawJob()
                    .setTitle("Pessoa Desenvolvedora Java " + i)
                    .setCompany("Empresa Medida " + (i / JOBS_PER_COMPANY))
                    .setLocation("Remoto")
                    .setDescriptionHtml("<p>Java, Spring Boot e PostgreSQL</p>")
                    .setUrl("https://remoteok.test/j/" + i)
                    .setWorkModelHint("remote")
                    .setPublishedAt(Instant.now())
                    .setSourceCode("remoteok"));
        }
        return jobs;
    }
}
