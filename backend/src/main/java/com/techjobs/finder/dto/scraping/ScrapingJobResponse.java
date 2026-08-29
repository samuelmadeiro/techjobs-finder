package com.techjobs.finder.dto.scraping;

import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.entity.ScrapingJobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Estado de uma coleta, como o cliente pode vê-lo.
 *
 * <p>O que está de fora é deliberado: {@code worker_id} identifica uma máquina interna,
 * {@code lease_until} e {@code next_attempt_at} descrevem a mecânica da fila, e
 * {@code filter_json} repete o que o cliente já mandou. Nada disso ajuda quem consulta, e
 * tudo isso conta a estranhos como o sistema é feito por dentro.
 *
 * <p>{@code lastError} só aparece quando há algo que o usuário possa entender e agir — a
 * mensagem curta que o scraper produziu, nunca stack trace. Enquanto o job ainda pode dar
 * certo, o erro da tentativa anterior seria ruído: o estado que vale é "está tentando".
 */
public record ScrapingJobResponse(
        UUID id,
        ScrapingJobStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        int attemptCount,
        int maxAttempts,
        String lastError) {

    public static ScrapingJobResponse from(ScrapingJob job) {
        boolean exposeError = job.getStatus() == ScrapingJobStatus.FAILED;
        return new ScrapingJobResponse(
                job.getId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                exposeError ? job.getLastError() : null);
    }
}
