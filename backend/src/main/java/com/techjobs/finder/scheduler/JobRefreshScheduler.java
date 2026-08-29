package com.techjobs.finder.scheduler;

import com.techjobs.finder.config.RateLimitProperties;
import com.techjobs.finder.config.ResumeProperties;
import com.techjobs.finder.config.SchedulerProperties;
import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.config.ScrapingWorkerProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ScrapingJob;
import com.techjobs.finder.repository.AppUserRepository;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.repository.ResumeRepository;
import com.techjobs.finder.repository.UserSessionRepository;
import com.techjobs.finder.service.ScrapingJobService;
import com.techjobs.finder.service.SearchCacheService;
import com.techjobs.finder.web.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantém a base aquecida sem depender do tráfego de usuários: a cada 30 minutos
 * coleta o feed geral das fontes e expira vagas que sumiram delas.
 *
 * <p>Intervalo deliberadamente conservador para não sobrecarregar as fontes.
 */
@Component
@ConditionalOnProperty(prefix = "techjobs.scheduler", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class JobRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobRefreshScheduler.class);
    private static final Duration REFRESH_BUDGET = Duration.ofMinutes(5);
    private static final Duration HARVEST_BUDGET = Duration.ofMinutes(20);

    private final ScrapingJobService scrapingJobService;
    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final AppUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final SearchCacheService cacheService;
    private final SchedulerProperties schedulerProperties;
    private final ScraperProperties scraperProperties;
    private final ScrapingWorkerProperties workerProperties;
    private final ResumeProperties resumeProperties;
    private final RateLimitProperties rateLimitProperties;
    private final RateLimiter rateLimiter;

    public JobRefreshScheduler(ScrapingJobService scrapingJobService,
                               JobRepository jobRepository,
                               ResumeRepository resumeRepository,
                               AppUserRepository userRepository,
                               UserSessionRepository sessionRepository,
                               SearchCacheService cacheService,
                               SchedulerProperties schedulerProperties,
                               ScraperProperties scraperProperties,
                               ScrapingWorkerProperties workerProperties,
                               ResumeProperties resumeProperties,
                               RateLimitProperties rateLimitProperties,
                               RateLimiter rateLimiter) {
        this.scrapingJobService = scrapingJobService;
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.cacheService = cacheService;
        this.schedulerProperties = schedulerProperties;
        this.scraperProperties = scraperProperties;
        this.workerProperties = workerProperties;
        this.resumeProperties = resumeProperties;
        this.rateLimitProperties = rateLimitProperties;
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(cron = "${techjobs.scheduler.refresh-cron}")
    public void refresh() {
        if (!scraperProperties.isEnabled()) {
            return;
        }
        // Filtro vazio: pega o feed geral de cada fonte, sem termo de busca.
        enqueue(JobSearchFilter.empty(), ScrapingJob.Mode.SEARCH, REFRESH_BUDGET, "refresh programado");
    }

    /**
     * Varredura profunda: pagina cada fonte até o limite configurado, sem termo de busca.
     * É o que faz a base crescer para milhares de vagas em vez de só as pesquisadas.
     */
    @Scheduled(cron = "${techjobs.scheduler.harvest-cron}")
    public void harvest() {
        if (!scraperProperties.isEnabled() || !scraperProperties.getHarvest().isEnabled()) {
            return;
        }
        enqueue(JobSearchFilter.empty(), ScrapingJob.Mode.HARVEST, HARVEST_BUDGET,
                "varredura agendada");
    }

    /**
     * Base vazia ou quase vazia na subida: já dispara uma varredura, para o usuário não
     * abrir a aplicação em uma tela sem vagas.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!scraperProperties.isEnabled() || !scraperProperties.getHarvest().isEnabled()) {
            return;
        }
        long active = jobRepository.countByActiveTrue();
        long threshold = scraperProperties.getHarvest().getBootstrapThreshold();
        if (active >= threshold) {
            log.info("Base já tem {} vaga(s) ativa(s); varredura inicial dispensada", active);
            return;
        }
        log.info("Base com {} vaga(s) (abaixo de {}); varredura inicial enfileirada", active,
                threshold);
        // Antes abria uma thread virtual e coletava ali mesmo, para não travar a subida. Agora
        // basta enfileirar: quem executa é o worker, e a aplicação sobe sem esperar nada.
        enqueue(JobSearchFilter.empty(), ScrapingJob.Mode.HARVEST, HARVEST_BUDGET,
                "varredura inicial");
    }

    /**
     * O scheduler não coleta mais: ele pede coleta.
     *
     * <p>Antes, {@code refresh()} chamava o orquestrador na própria thread do agendador e
     * {@code bootstrap()} abria uma thread virtual. Eram um terceiro e um quarto mecanismo de
     * execução, cada um com seu tratamento de erro, nenhum com retry, nenhum visível fora do
     * log — e, com duas instâncias, os dois rodavam a mesma coleta ao mesmo tempo. Enfileirar
     * coloca o trabalho do scheduler exatamente na mesma fila da busca sob demanda: mesmo
     * claim, mesma lease, mesmo retry, e uma execução só entre todas as instâncias.
     *
     * <p>Sem usuário dono: {@code requested_by_user_id} nulo é o que marca trabalho interno.
     */
    private void enqueue(JobSearchFilter filter, ScrapingJob.Mode mode, Duration budget,
                         String label) {
        ScrapingJobService.Enqueued enqueued = scrapingJobService.enqueue(filter, mode, budget, null);
        if (enqueued.created()) {
            log.info("Coleta de {} enfileirada: jobId={}", label, enqueued.job().getId());
        } else {
            log.info("Coleta de {} dispensada: job {} já está ativo para o mesmo trabalho",
                    label, enqueued.job().getId());
        }
    }

    @Scheduled(cron = "${techjobs.scheduler.cleanup-cron}")
    @Transactional
    public void cleanup() {
        Instant staleThreshold = Instant.now().minus(schedulerProperties.getStaleAfter());
        Instant purgeThreshold = Instant.now().minus(schedulerProperties.getPurgeAfter());
        Instant resumeThreshold = Instant.now().minus(resumeProperties.getRetention());

        int deactivated = jobRepository.deactivateStale(staleThreshold);
        int purged = jobRepository.purgeInactive(purgeThreshold);
        int cacheEvicted = cacheService.evictOlderThan(purgeThreshold);
        // Ordem importa: o usuário só é considerado órfão depois de o currículo dele sair.
        int resumesPurged = resumeRepository.deleteOlderThan(resumeThreshold);
        // Sessão vencida não autentica mais ninguém, mas continua ocupando a tabela e
        // guardando o user agent de quem a abriu. Sem esta linha, user_session só crescia.
        int sessionsPurged = sessionRepository.deleteExpiredBefore(Instant.now());
        int bucketsPurged = rateLimiter.purgeIdleBefore(
                Instant.now().minus(rateLimitProperties.getResumeUpload().getRetainIdleFor()));
        int usersPurged = userRepository.deleteAbandonedOlderThan(resumeThreshold);
        // Job terminado é histórico, não acervo: sem esta linha, scraping_job só cresceria.
        int scrapingJobsPurged = scrapingJobService.purgeHistory(
                Instant.now().minus(workerProperties.getHistoryRetention()));

        log.info("Limpeza: {} vaga(s) desativada(s), {} removida(s), {} entrada(s) de cache "
                        + "expirada(s), {} sessão(ões) vencida(s), {} currículo(s) e {} usuário(s) "
                        + "sem currículo removido(s), {} balde(s) de limite, {} job(s) de "
                        + "scraping antigo(s)",
                deactivated, purged, cacheEvicted, sessionsPurged, resumesPurged, usersPurged,
                bucketsPurged, scrapingJobsPurged);
    }
}
