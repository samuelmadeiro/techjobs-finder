package com.techjobs.finder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.config.ScrapingWorkerProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.entity.ScrapingJobStatus;
import com.techjobs.finder.exception.ResourceNotFoundException;
import com.techjobs.finder.repository.ScrapingJobRepository;
import com.techjobs.finder.security.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida do job de scraping: criar, reivindicar, concluir, retentar, recuperar.
 *
 * <p>Todo estado mora no Postgres. Esta classe não guarda nada em memória de propósito — é o
 * que permite duas instâncias trabalharem na mesma fila sem combinar nada entre si, e é o que
 * faltava quando a coleta era um {@code Runnable} num executor.
 */
@Service
public class ScrapingJobService {

    private static final Logger log = LoggerFactory.getLogger(ScrapingJobService.class);

    /** Cabe em {@code last_error VARCHAR(500)} com folga para o sufixo de truncamento. */
    private static final int MAX_ERROR_LENGTH = 480;

    private final ScrapingJobRepository repository;
    private final ScrapingWorkerProperties properties;
    private final ScrapingJobMetrics metrics;
    private final ObjectMapper objectMapper;

    public ScrapingJobService(ScrapingJobRepository repository,
                              ScrapingWorkerProperties properties,
                              ScrapingJobMetrics metrics,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * Por que a tentativa falhou, no detalhe que o código realmente fornece.
     *
     * <p>Não há classificação mais fina porque não há informação mais fina: o
     * {@code HttpFetcher} traduz tudo para {@code ScraperException} com mensagem, e o
     * {@code ScrapeResult} carrega essa mensagem. Inventar categorias sem sinal por trás só
     * produziria decisão errada com aparência de precisão.
     */
    public enum FailureKind {
        /** Rede, 5xx, tempo esgotado: tenta de novo com backoff normal. */
        TRANSIENT,
        /** A fonte pediu para desacelerar (HTTP 429): tenta de novo, bem mais tarde. */
        THROTTLED,
        /** robots.txt fecha, host bloqueado, 401/403: tentar de novo não muda o resultado. */
        PERMANENT
    }

    /** Resultado do enfileiramento, para quem chamou saber se criou ou reaproveitou. */
    public record Enqueued(ScrapingJob job, boolean created) {
    }

    // ------------------------------------------------------------------ criação

    /**
     * Garante que exista uma execução ativa para este trabalho.
     *
     * <p>Idempotente por construção: se já houver QUEUED ou RUNNING para o mesmo
     * {@code (fingerprint, mode)}, devolve aquela em vez de criar outra. É o que faz cem
     * requisições do mesmo filtro virarem um scraping só — inclusive entre instâncias, porque
     * quem recusa a segunda inserção é o índice único parcial, não código Java.
     *
     * <p>Transação própria: enfileirar é um efeito que deve sobreviver mesmo que a requisição
     * que o pediu falhe adiante, e não deve arrastar a transação de quem chamou.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Enqueued enqueue(JobSearchFilter filter, ScrapingJob.Mode mode, Duration budget,
                            Long requestedByUserId) {
        String fingerprint = filter.fingerprint();
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();

        int inserted = repository.insertIfNoActive(id, fingerprint, mode.name(),
                serialize(filter), truncate(filter.toQueryText(), 500),
                (int) Math.max(1, budget.toSeconds()), requestedByUserId,
                properties.getMaxAttempts(), now);

        if (inserted == 0) {
            Optional<ScrapingJob> active = repository.findActive(fingerprint, mode);
            if (active.isPresent()) {
                metrics.duplicateSuppressed();
                log.debug("JOB_DUPLICATE_SUPPRESSED jobId={} fingerprint={} mode={}",
                        active.get().getId(), fingerprint, mode);
                return new Enqueued(active.get(), false);
            }
            // Corrida estreita: a execução ativa terminou entre o INSERT e esta leitura.
            // Tentar de novo agora encontra o índice livre.
            inserted = repository.insertIfNoActive(id, fingerprint, mode.name(),
                    serialize(filter), truncate(filter.toQueryText(), 500),
                    (int) Math.max(1, budget.toSeconds()), requestedByUserId,
                    properties.getMaxAttempts(), now);
            if (inserted == 0) {
                ScrapingJob existing = repository.findActive(fingerprint, mode).orElseThrow(
                        () -> new IllegalStateException("Job ativo desapareceu para " + fingerprint));
                return new Enqueued(existing, false);
            }
        }

        ScrapingJob job = repository.findById(id).orElseThrow();
        metrics.created();
        log.info("JOB_CREATED jobId={} fingerprint={} mode={} attempt={} manual={}",
                job.getId(), fingerprint, mode, job.getAttemptCount(), requestedByUserId != null);
        return new Enqueued(job, true);
    }

    // ------------------------------------------------------------------ consulta

    /**
     * Job pedido por este usuário.
     *
     * <p>Job de outro usuário e job inexistente respondem igual, com a mesma exceção: revelar
     * "existe, mas não é seu" já é informação sobre o que o vizinho está coletando. Job do
     * scheduler ({@code requested_by_user_id} nulo) não pertence a ninguém e também não é
     * consultável por esta rota.
     */
    @Transactional(readOnly = true)
    public ScrapingJob findOwned(UUID id, AuthenticatedUser current) {
        return repository.findById(id)
                .filter(job -> current != null && job.belongsTo(current.id()))
                // Mesma exceção e mesma mensagem dos dois casos — inexistente e de outro
                // dono. O id repetido na mensagem é o que o próprio cliente enviou.
                .orElseThrow(() -> new ResourceNotFoundException("Coleta", id));
    }

    @Transactional(readOnly = true)
    public Optional<ScrapingJob> find(UUID id) {
        return repository.findById(id);
    }

    // ------------------------------------------------------------------ claim

    /**
     * Reivindica a próxima execução elegível para este worker.
     *
     * <p>Duas instruções numa transação só: {@code SELECT ... FOR UPDATE SKIP LOCKED} escolhe
     * e tranca a linha, o {@code UPDATE} a tira da fila. Entre as duas, nenhum outro worker
     * enxerga a linha como candidata — quem chegar junto pula para a próxima em vez de
     * esperar. É o que garante "worker A pega X, worker B não pega X" com qualquer número de
     * processos.
     *
     * <p>Ler e depois gravar sem o lock daria certo em teste de uma thread e executaria o
     * mesmo scraping duas vezes em produção.
     */
    @Transactional
    public Optional<ScrapingJob> claimNext(String workerId) {
        Instant now = Instant.now();
        Optional<UUID> candidate = repository.lockNextEligible(now);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        UUID id = candidate.get();
        ScrapingJob queued = repository.findById(id).orElse(null);
        if (queued == null) {
            return Optional.empty();
        }
        Instant leaseUntil = now.plus(leaseFor(queued));
        if (repository.markRunning(id, workerId, now, leaseUntil) == 0) {
            return Optional.empty();
        }
        // O UPDATE foi por SQL nativo: o contexto de persistência ainda tem a versão antiga.
        repository.flush();
        ScrapingJob claimed = repository.findById(id).orElseThrow();
        metrics.claimed();
        log.info("JOB_CLAIMED jobId={} fingerprint={} attempt={} worker={} leaseUntil={}",
                claimed.getId(), claimed.getFingerprint(), claimed.getAttemptCount(), workerId,
                leaseUntil);
        return Optional.of(claimed);
    }

    /** Quanto tempo a lease cobre: o orçamento da coleta mais a folga da ingestão. */
    public Duration leaseFor(ScrapingJob job) {
        return Duration.ofSeconds(job.getBudgetSeconds()).plus(properties.getLeaseMargin());
    }

    /** Teto de execução antes de o worker cancelar a própria tarefa. Sempre menor que a lease. */
    public Duration executionTimeoutFor(ScrapingJob job) {
        return Duration.ofSeconds(job.getBudgetSeconds())
                .plus(properties.getExecutionTimeoutMargin());
    }

    // ------------------------------------------------------------------ conclusão

    @Transactional
    public void complete(ScrapingJob job, int collected, Duration elapsed) {
        repository.markCompleted(job.getId(), Instant.now());
        metrics.completed(elapsed);
        log.info("JOB_COMPLETED jobId={} fingerprint={} attempt={} collected={} elapsedMs={}",
                job.getId(), job.getFingerprint(), job.getAttemptCount(), collected,
                elapsed.toMillis());
    }

    /**
     * Registra a falha da tentativa e decide entre nova tentativa e desistência.
     *
     * <p>Desiste sem gastar tentativa quando o erro é permanente: robots.txt fechado ou host
     * bloqueado não muda em dez minutos, e insistir só transforma configuração errada em
     * tráfego repetido contra quem já disse não.
     */
    @Transactional
    public void fail(ScrapingJob job, FailureKind kind, String message) {
        Instant now = Instant.now();
        String error = truncate(message, MAX_ERROR_LENGTH);
        boolean exhausted = job.getAttemptCount() >= job.getMaxAttempts();

        if (kind == FailureKind.PERMANENT || exhausted) {
            repository.markFailed(job.getId(), now, error);
            metrics.failed();
            log.warn("JOB_FAILED jobId={} fingerprint={} attempt={}/{} kind={} error={}",
                    job.getId(), job.getFingerprint(), job.getAttemptCount(),
                    job.getMaxAttempts(), kind, error);
            return;
        }

        Duration backoff = backoffFor(job.getAttemptCount(), kind);
        Instant nextAttemptAt = now.plus(backoff);
        repository.scheduleRetry(job.getId(), nextAttemptAt, error);
        metrics.retryScheduled();
        log.warn("JOB_RETRY_SCHEDULED jobId={} fingerprint={} attempt={}/{} kind={} "
                        + "backoffSeconds={} nextAttemptAt={} error={}",
                job.getId(), job.getFingerprint(), job.getAttemptCount(), job.getMaxAttempts(),
                kind, backoff.toSeconds(), nextAttemptAt, error);
    }

    /**
     * Backoff exponencial a partir da tentativa já consumida, limitado por
     * {@code max-backoff}. Com os padrões: 30 s, 1 min, 2 min...
     *
     * <p>Fonte pedindo calma (429) não entra nessa escala: espera o
     * {@code throttled-backoff}, que é da ordem de minutos desde a primeira vez. Trinta
     * segundos depois de um 429 é bater na mesma porta.
     */
    public Duration backoffFor(int attemptCount, FailureKind kind) {
        int exponent = Math.max(0, attemptCount - 1);
        long millis = properties.getInitialBackoff().toMillis() << Math.min(exponent, 20);
        Duration backoff = Duration.ofMillis(Math.min(millis, properties.getMaxBackoff().toMillis()));
        if (kind == FailureKind.THROTTLED && backoff.compareTo(properties.getThrottledBackoff()) < 0) {
            return properties.getThrottledBackoff();
        }
        return backoff;
    }

    /**
     * Classifica a falha pelo que a mensagem do scraper diz.
     *
     * <p>Textual porque é o que existe: {@code ScrapeResult} carrega {@code errorMessage}, não
     * código HTTP. Não há leitura de {@code Retry-After} — o {@code HttpFetcher} não expõe os
     * cabeçalhos da resposta —, então 429 usa o {@code throttled-backoff} configurado.
     */
    public FailureKind classify(String message) {
        if (message == null) {
            return FailureKind.TRANSIENT;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("429")) {
            return FailureKind.THROTTLED;
        }
        if (lower.contains("robots.txt")
                || lower.contains("http 401")
                || lower.contains("http 403")
                || lower.contains("acesso bloqueado")
                || lower.contains("host não permitido")
                || lower.contains("esquema não permitido")
                || lower.contains("url inválida")) {
            return FailureKind.PERMANENT;
        }
        return FailureKind.TRANSIENT;
    }

    // ------------------------------------------------------------------ manutenção

    /**
     * Devolve à fila as execuções cujo worker sumiu.
     *
     * <p>Sem isto, um processo morto deixaria a linha RUNNING para sempre — e, como o índice
     * único conta RUNNING como execução ativa, aquele filtro nunca mais seria coletado.
     */
    @Transactional
    public int recoverExpiredLeases() {
        Instant now = Instant.now();
        int recovered = repository.recoverExpiredLeases(now, now,
                "Lease expirada: o worker não concluiu a execução");
        if (recovered > 0) {
            metrics.leaseRecovered(recovered);
            log.warn("JOB_LEASE_RECOVERED count={}", recovered);
        }
        return recovered;
    }

    @Transactional
    public int purgeHistory(Instant threshold) {
        return repository.deleteTerminalOlderThan(threshold);
    }

    @Transactional(readOnly = true)
    public long count(ScrapingJobStatus status) {
        return repository.countByStatus(status);
    }

    // ------------------------------------------------------------------ apoio

    private String serialize(JobSearchFilter filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Filtro de busca não serializável", e);
        }
    }

    /** Reconstrói o filtro guardado na linha. O fingerprint é hash: não dá para voltar dele. */
    public JobSearchFilter deserialize(ScrapingJob job) {
        try {
            return objectMapper.readValue(job.getFilterJson(), JobSearchFilter.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Filtro gravado no job " + job.getId()
                    + " não pôde ser lido", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
