package com.techjobs.finder.entity;

/**
 * Ciclo de vida de uma execução de coleta.
 *
 * <pre>
 *   QUEUED ──claim──▶ RUNNING ──sucesso──▶ COMPLETED
 *      ▲                 │
 *      │                 ├──falha, ainda há tentativa──▶ QUEUED (com next_attempt_at futuro)
 *      │                 │
 *      └─lease vencida───┴──falha na última tentativa──▶ FAILED
 * </pre>
 *
 * <p>Quatro estados e não cinco: CANCELLED não entra porque nada no sistema cancela uma
 * coleta. Quem pede não espera o resultado (a busca já respondeu do banco), e o scheduler
 * não tem por que desistir de um trabalho que ele mesmo enfileirou. Um estado sem transição
 * que o alcance seria só nome em enum.
 */
public enum ScrapingJobStatus {

    /** Esperando worker. Elegível a partir de {@code next_attempt_at}. */
    QUEUED,

    /** Reivindicada por um worker, com lease válida até {@code lease_until}. */
    RUNNING,

    /** Coleta e ingestão concluídas. Estado final. */
    COMPLETED,

    /** Esgotou {@code max_attempts}, ou falhou de forma que não admite nova tentativa. */
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
