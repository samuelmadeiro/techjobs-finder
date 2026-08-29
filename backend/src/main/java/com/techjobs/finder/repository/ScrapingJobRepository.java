package com.techjobs.finder.repository;

import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.entity.ScrapingJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Todas as transições de estado de um job são UPDATE condicional.
 *
 * <p>O motivo é sempre o mesmo: mais de uma instância da aplicação disputa as mesmas linhas.
 * Ler a entidade, decidir em Java e gravar de volta funcionaria com um processo só e
 * silenciosamente executaria o mesmo scraping duas vezes com dois. Quem decide é o Postgres.
 */
public interface ScrapingJobRepository extends JpaRepository<ScrapingJob, UUID> {

    /**
     * Cria a execução, a menos que já exista uma ativa para o mesmo trabalho.
     *
     * <p>O árbitro é {@code uq_scraping_job_active}, o índice único parcial sobre
     * {@code (fingerprint, mode) WHERE status IN ('QUEUED','RUNNING')}. É preciso repetir a
     * cláusula parcial no {@code ON CONFLICT} para o Postgres inferir esse índice, e não a
     * chave primária.
     *
     * <p>É isto que transforma cem requisições do mesmo filtro em um scraping: as noventa e
     * nove que perdem recebem 0 aqui. E o índice não impede coletar a mesma combinação
     * amanhã, porque linha terminada (COMPLETED/FAILED) sai do índice — que é a diferença
     * entre "não duplique execução ativa" e um UNIQUE(fingerprint) que congelaria o filtro
     * para sempre.
     *
     * <p>{@code ON CONFLICT DO NOTHING} concorrente não é corrida: o Postgres espera a
     * transação conflitante terminar antes de decidir.
     *
     * @return 1 quando esta chamada criou a execução; 0 quando já havia uma ativa
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO scraping_job (id, fingerprint, mode, filter_json, query_text, status,
                                      budget_seconds, requested_by_user_id, created_at,
                                      next_attempt_at, attempt_count, max_attempts)
            VALUES (:id, :fingerprint, :mode, :filterJson, :queryText, 'QUEUED',
                    :budgetSeconds, :userId, :now, :now, 0, :maxAttempts)
            ON CONFLICT (fingerprint, mode) WHERE status IN ('QUEUED', 'RUNNING') DO NOTHING
            """)
    int insertIfNoActive(@Param("id") UUID id,
                         @Param("fingerprint") String fingerprint,
                         @Param("mode") String mode,
                         @Param("filterJson") String filterJson,
                         @Param("queryText") String queryText,
                         @Param("budgetSeconds") int budgetSeconds,
                         @Param("userId") Long userId,
                         @Param("maxAttempts") int maxAttempts,
                         @Param("now") Instant now);

    @Query("""
            SELECT j FROM ScrapingJob j
            WHERE j.fingerprint = :fingerprint AND j.mode = :mode
              AND j.status IN (com.techjobs.finder.entity.ScrapingJobStatus.QUEUED,
                               com.techjobs.finder.entity.ScrapingJobStatus.RUNNING)
            """)
    Optional<ScrapingJob> findActive(@Param("fingerprint") String fingerprint,
                                     @Param("mode") ScrapingJob.Mode mode);

    /**
     * Escolhe e tranca a próxima execução elegível.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} é o que faz dois workers pegarem jobs
     * <em>diferentes</em> em vez de um esperar o outro: quem chega depois pula a linha
     * trancada e leva a seguinte. O lock vale até o fim da transação, então o
     * {@link #markRunning} que vem a seguir precisa rodar na mesma transação — é lá que a
     * linha sai de QUEUED e deixa de ser candidata.
     */
    @Query(nativeQuery = true, value = """
            SELECT id FROM scraping_job
            WHERE status = 'QUEUED' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """)
    Optional<UUID> lockNextEligible(@Param("now") Instant now);

    /**
     * Fecha o claim: a linha vira RUNNING, com dono e prazo.
     *
     * <p>{@code attempt_count} sobe aqui, e não no erro: o que interessa contar é tentativa
     * de execução iniciada. Um processo que morre no meio já consumiu a tentativa, e sem
     * isso um job que sempre derruba o worker seria retentado para sempre.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE scraping_job
               SET status = 'RUNNING',
                   worker_id = :workerId,
                   started_at = :now,
                   lease_until = :leaseUntil,
                   attempt_count = attempt_count + 1
             WHERE id = :id AND status = 'QUEUED'
            """)
    int markRunning(@Param("id") UUID id,
                    @Param("workerId") String workerId,
                    @Param("now") Instant now,
                    @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE scraping_job
               SET status = 'COMPLETED',
                   completed_at = :now,
                   worker_id = NULL,
                   lease_until = NULL,
                   last_error = NULL
             WHERE id = :id AND status = 'RUNNING'
            """)
    int markCompleted(@Param("id") UUID id, @Param("now") Instant now);

    /** Volta para a fila com o instante da próxima tentativa já calculado pelo backoff. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE scraping_job
               SET status = 'QUEUED',
                   next_attempt_at = :nextAttemptAt,
                   last_error = :error,
                   worker_id = NULL,
                   lease_until = NULL
             WHERE id = :id AND status = 'RUNNING'
            """)
    int scheduleRetry(@Param("id") UUID id,
                      @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE scraping_job
               SET status = 'FAILED',
                   completed_at = :now,
                   last_error = :error,
                   worker_id = NULL,
                   lease_until = NULL
             WHERE id = :id AND status = 'RUNNING'
            """)
    int markFailed(@Param("id") UUID id, @Param("now") Instant now, @Param("error") String error);

    /**
     * Recupera execuções cujo dono sumiu — processo morto, contêiner reciclado, rede caída.
     *
     * <p>Sem isto, um RUNNING órfão ficaria eternamente no índice de execuções ativas e
     * bloquearia todo pedido novo daquele fingerprint: o sistema pararia de coletar aquele
     * filtro sem ninguém estar coletando.
     *
     * <p>A linha volta para QUEUED, ou vira FAILED se as tentativas já acabaram.
     * {@code attempt_count} não é tocado — ele já subiu no claim que ficou órfão. Nenhum job
     * novo é criado: é a mesma linha que retoma, então o índice único continua valendo.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE scraping_job
               SET status = CASE WHEN attempt_count >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
                   completed_at = CASE WHEN attempt_count >= max_attempts
                                       THEN CAST(:now AS timestamptz) ELSE NULL END,
                   next_attempt_at = CASE WHEN attempt_count >= max_attempts
                                          THEN next_attempt_at
                                          ELSE CAST(:retryAt AS timestamptz) END,
                   last_error = :error,
                   worker_id = NULL,
                   lease_until = NULL
             WHERE status = 'RUNNING' AND lease_until < :now
            """)
    int recoverExpiredLeases(@Param("now") Instant now,
                             @Param("retryAt") Instant retryAt,
                             @Param("error") String error);

    long countByStatus(ScrapingJobStatus status);

    List<ScrapingJob> findByStatus(ScrapingJobStatus status);

    /** Histórico não é acervo: linha terminada velha só ocupa espaço. */
    @Modifying
    @Query("""
            DELETE FROM ScrapingJob j
             WHERE j.completedAt IS NOT NULL AND j.completedAt < :threshold
            """)
    int deleteTerminalOlderThan(@Param("threshold") Instant threshold);
}
