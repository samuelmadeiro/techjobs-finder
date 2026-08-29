package com.techjobs.finder.service;

import com.techjobs.finder.entity.ScrapingJobStatus;
import com.techjobs.finder.repository.ScrapingJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Instrumentação da fila de scraping.
 *
 * <p>Usa o Micrometer que o Actuator já traz e que já publica em {@code /actuator/metrics} —
 * nenhuma infraestrutura nova. Os contadores também são lidos diretamente pelos testes, que
 * é como se afirma "não houve execução duplicada" sem depender de log.
 *
 * <p>Fila e execuções em andamento são <em>gauges</em> lidos do banco, não contadores em
 * memória: com várias instâncias, o número que interessa é o da fila inteira, e ele não cabe
 * na memória de um processo.
 */
@Component
public class ScrapingJobMetrics {

    private final Counter created;
    private final Counter duplicateSuppressed;
    private final Counter claimed;
    private final Counter completed;
    private final Counter failed;
    private final Counter retries;
    private final Counter leaseRecoveries;
    private final Timer executionTimer;

    /** Contadores espelhados: {@link Counter} não é feito para asserção exata em teste. */
    private final AtomicLong createdCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final AtomicLong claimedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong leaseRecoveredCount = new AtomicLong();

    public ScrapingJobMetrics(MeterRegistry registry, ScrapingJobRepository repository) {
        this.created = Counter.builder("scraping.jobs.created")
                .description("Jobs de scraping criados").register(registry);
        this.duplicateSuppressed = Counter.builder("scraping.jobs.duplicate.suppressed")
                .description("Pedidos que reaproveitaram um job ativo em vez de criar outro")
                .register(registry);
        this.claimed = Counter.builder("scraping.jobs.claimed")
                .description("Tentativas de execução iniciadas por um worker").register(registry);
        this.completed = Counter.builder("scraping.jobs.completed")
                .description("Jobs concluídos com sucesso").register(registry);
        this.failed = Counter.builder("scraping.jobs.failed")
                .description("Jobs encerrados sem sucesso").register(registry);
        this.retries = Counter.builder("scraping.jobs.retries")
                .description("Novas tentativas agendadas").register(registry);
        this.leaseRecoveries = Counter.builder("scraping.jobs.lease.recovered")
                .description("Execuções retomadas após lease vencida").register(registry);
        this.executionTimer = Timer.builder("scraping.jobs.execution")
                .description("Duração da execução de um job, do claim à conclusão")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        Gauge.builder("scraping.jobs.queued", repository,
                        repo -> repo.countByStatus(ScrapingJobStatus.QUEUED))
                .description("Jobs esperando worker").register(registry);
        Gauge.builder("scraping.jobs.running", repository,
                        repo -> repo.countByStatus(ScrapingJobStatus.RUNNING))
                .description("Jobs em execução").register(registry);
    }

    void created() {
        created.increment();
        createdCount.incrementAndGet();
    }

    void duplicateSuppressed() {
        duplicateSuppressed.increment();
        duplicateCount.incrementAndGet();
    }

    void claimed() {
        claimed.increment();
        claimedCount.incrementAndGet();
    }

    void completed(Duration elapsed) {
        completed.increment();
        completedCount.incrementAndGet();
        executionTimer.record(elapsed);
    }

    void failed() {
        failed.increment();
        failedCount.incrementAndGet();
    }

    void retryScheduled() {
        retries.increment();
        retryCount.incrementAndGet();
    }

    void leaseRecovered(int count) {
        leaseRecoveries.increment(count);
        leaseRecoveredCount.addAndGet(count);
    }

    /** Instantâneo para testes e diagnóstico. */
    public record Snapshot(long created, long duplicateSuppressed, long claimed, long completed,
                           long failed, long retries, long leaseRecovered) {
    }

    public Snapshot snapshot() {
        return new Snapshot(createdCount.get(), duplicateCount.get(), claimedCount.get(),
                completedCount.get(), failedCount.get(), retryCount.get(),
                leaseRecoveredCount.get());
    }
}
