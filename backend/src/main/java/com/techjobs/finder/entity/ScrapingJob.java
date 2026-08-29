package com.techjobs.finder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma execução de coleta, com estado no banco em vez de na memória de uma JVM.
 *
 * <p>A entidade é usada para <em>ler</em> a linha. As transições de estado — claim,
 * conclusão, retry, recuperação de lease — acontecem em UPDATE condicional no repositório,
 * não por dirty checking: são justamente as operações em que duas instâncias disputam a
 * mesma linha, e ler-modificar-gravar perderia a disputa.
 */
@Entity
@Table(name = "scraping_job")
public class ScrapingJob {

    /** Como a coleta é feita. Espelha {@code ScraperOrchestrator.Mode}. */
    public enum Mode {
        /** Feed filtrado, orçamento curto: origem em busca de usuário ou refresh programado. */
        SEARCH,
        /** Varredura profunda paginada, orçamento longo. */
        HARVEST
    }

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode;

    @Column(name = "filter_json", nullable = false)
    private String filterJson;

    @Column(name = "query_text", length = 500)
    private String queryText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScrapingJobStatus status;

    @Column(name = "budget_seconds", nullable = false)
    private int budgetSeconds;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "worker_id", length = 120)
    private String workerId;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    protected ScrapingJob() {
    }

    public UUID getId() {
        return id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Mode getMode() {
        return mode;
    }

    public String getFilterJson() {
        return filterJson;
    }

    public String getQueryText() {
        return queryText;
    }

    public ScrapingJobStatus getStatus() {
        return status;
    }

    public int getBudgetSeconds() {
        return budgetSeconds;
    }

    public Long getRequestedByUserId() {
        return requestedByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Identificador da instância que segura a lease. Diagnóstico interno: nunca sai em
     * resposta de API — ver {@code ScrapingJobResponse}.
     */
    public String getWorkerId() {
        return workerId;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    /** Dono do pedido manual. {@code null} significa trabalho interno do scheduler. */
    public boolean belongsTo(Long userId) {
        return requestedByUserId != null && requestedByUserId.equals(userId);
    }
}
