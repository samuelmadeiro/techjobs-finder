package com.techjobs.finder.service;

import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.config.SearchProperties;
import com.techjobs.finder.dto.PageResponse;
import com.techjobs.finder.dto.job.JobDetailsResponse;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.dto.job.SearchMeta;
import com.techjobs.finder.dto.recommendation.CompatibilityResult;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.exception.ResourceNotFoundException;
import com.techjobs.finder.mapper.JobMapper;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.repository.JobSpecifications;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.scraper.JobScraper;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import com.techjobs.finder.service.ResumeMatchingService.ResumeProfile;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra o fluxo completo da busca: cache -> coleta -> ingestão -> consulta ->
 * pontuação -> ordenação -> paginação.
 *
 * <p>Quando o pedido traz um token de currículo, cada vaga sai da API já com a
 * compatibilidade calculada; em {@link #recommend} a compatibilidade também define a ordem.
 *
 * <p>O método público não é transacional de propósito: a coleta em rede pode levar
 * segundos e não deve segurar uma conexão do pool aberta.
 */
@Service
public class JobSearchService {

    private static final Logger log = LoggerFactory.getLogger(JobSearchService.class);

    private final JobRepository jobRepository;
    private final JobMapper mapper;
    private final RelevanceScorer scorer;
    private final ScraperOrchestrator orchestrator;
    private final SearchCacheService cacheService;
    private final SearchRefreshService refreshService;
    private final RecommendationService recommendationService;
    private final SearchProperties properties;
    private final CacheManager cacheManager;
    private final CountryCatalog countryCatalog;

    public JobSearchService(JobRepository jobRepository,
                            JobMapper mapper,
                            RelevanceScorer scorer,
                            ScraperOrchestrator orchestrator,
                            SearchCacheService cacheService,
                            SearchRefreshService refreshService,
                            RecommendationService recommendationService,
                            SearchProperties properties,
                            CacheManager cacheManager,
                            CountryCatalog countryCatalog) {
        this.jobRepository = jobRepository;
        this.mapper = mapper;
        this.scorer = scorer;
        this.orchestrator = orchestrator;
        this.cacheService = cacheService;
        this.refreshService = refreshService;
        this.recommendationService = recommendationService;
        this.properties = properties;
        this.cacheManager = cacheManager;
        this.countryCatalog = countryCatalog;
    }

    /** Busca comum: ordena pelo critério pedido e anexa compatibilidade quando houver currículo. */
    public PageResponse<JobSummaryResponse> search(JobSearchRequest request, AuthenticatedUser current) {
        return execute(request, current, false);
    }

    /** Recomendação: mesma busca, mas ordenada pela compatibilidade com o currículo. */
    public PageResponse<JobSummaryResponse> recommend(JobSearchRequest request, AuthenticatedUser current) {
        return execute(request, current, true);
    }

    private PageResponse<JobSummaryResponse> execute(JobSearchRequest request,
                                                     AuthenticatedUser current,
                                                     boolean sortByCompatibility) {
        JobSearchFilter filter = request.toFilter(countryCatalog);
        int size = Math.min(request.getSize(), properties.getMaxPageSize());

        SearchMeta meta = ensureFreshData(filter, request.isRefresh());
        Optional<ResumeProfile> profile = recommendationService.profileFor(current);

        // Ordenação por data ou por empresa é trabalho do banco: existe coluna (ou expressão
        // indexada) para ordenar, então a página sai pronta com LIMIT/OFFSET em vez de
        // carregar milhares de candidatos para devolver vinte.
        //
        // Relevância e compatibilidade não têm coluna: a nota é calculada a partir do filtro
        // e do currículo, no momento da requisição. Para elas o caminho continua sendo
        // pontuar o conjunto de candidatos e ordenar aqui — é inerente ao algoritmo, não
        // uma escolha de implementação.
        // Página já montada de uma requisição anterior, quando ela vale para qualquer um.
        String cacheKey = shareableKey(filter, request, size, profile);
        if (cacheKey != null && !request.isRefresh()) {
            PageResponse<JobSummaryResponse> cached = readFromCache(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        PageResponse<JobSummaryResponse> response;
        if (!sortByCompatibility && request.sortMode() != JobSearchRequest.SortMode.RELEVANCE) {
            response = paginateInDatabase(filter, request, size, profile, meta);
        } else {
            List<JobSummaryResponse> ranked =
                    rank(filter, request.sortMode(), profile, sortByCompatibility);
            // Bateu no teto: existem mais vagas compatíveis do que as pontuadas nesta resposta.
            boolean truncated = ranked.size() >= properties.getCandidateLimit();
            response = PageResponse.of(ranked, request.getPage(), size,
                    meta.withTruncated(truncated));
        }

        if (cacheKey != null) {
            writeToCache(cacheKey, response);
        }
        return response;
    }


    /**
     * Chave da página no cache, ou {@code null} quando a resposta não pode ser compartilhada.
     *
     * <p>Só entra no cache a resposta de quem <strong>não</strong> tem currículo: com
     * currículo, cada vaga carrega a compatibilidade daquela pessoa, e guardar isso sob uma
     * chave compartilhada entregaria o cálculo de um usuário para outro. A busca de quem tem
     * currículo continua rápida pelo motivo principal — ela não espera mais os scrapers.
     *
     * <p>A chave sai do fingerprint do filtro já normalizado (listas ordenadas, vazios
     * viram nulo, texto sem acento e em minúsculas), somado ao que muda a página: ordenação,
     * número e tamanho. Dois filtros semanticamente iguais produzem a mesma chave; qualquer
     * diferença que altere o resultado produz outra.
     *
     * <p>O prefixo {@code v1} versiona o formato: mudar a estrutura da resposta vira
     * {@code v2} e as entradas antigas deixam de ser lidas, sem precisar limpar nada.
     */
    private String shareableKey(JobSearchFilter filter, JobSearchRequest request, int size,
                                Optional<ResumeProfile> profile) {
        if (profile.isPresent()) {
            return null;
        }
        return "v1:%s:%s:%d:%d".formatted(filter.fingerprint(), request.sortMode(),
                request.getPage(), size);
    }

    @SuppressWarnings("unchecked")
    private PageResponse<JobSummaryResponse> readFromCache(String key) {
        Cache cache = cacheManager.getCache(CacheConfig.SEARCH_CACHE);
        if (cache == null) {
            return null;
        }
        try {
            Cache.ValueWrapper wrapper = cache.get(key);
            return wrapper == null ? null : (PageResponse<JobSummaryResponse>) wrapper.get();
        } catch (RuntimeException e) {
            // Cache indisponível não pode virar erro: a página é remontada a partir do banco.
            log.warn("Falha ao ler a busca do cache; seguindo para o banco", e);
            return null;
        }
    }

    private void writeToCache(String key, PageResponse<JobSummaryResponse> response) {
        Cache cache = cacheManager.getCache(CacheConfig.SEARCH_CACHE);
        if (cache == null) {
            return;
        }
        try {
            cache.put(key, response);
        } catch (RuntimeException e) {
            log.warn("Falha ao gravar a busca no cache", e);
        }
    }

    /**
     * Decide o que fazer com a atualização — e nunca espera por ela.
     *
     * <p>Esta era a origem da lentidão ao aplicar filtros: um filtro novo não tinha marca de
     * coleta, então a requisição chamava os scrapers e a ingestão antes de montar a resposta.
     * O usuário esperava até o orçamento inteiro de 20 segundos por dados que, na maioria das
     * vezes, mal mudariam o que o banco já podia responder na hora.
     *
     * <p>Agora o resultado do banco sai imediatamente nos três casos, e a coleta acontece
     * atrás:
     *
     * <pre>
     *   FRESCO → responde; não coleta
     *   VELHO  → responde; agenda coleta
     *   AUSENTE→ responde com o que houver no banco; agenda coleta
     * </pre>
     *
     * <p>{@code refresh=true} não muda a fonte da resposta — ela continua vindo do banco —,
     * apenas força o agendamento da coleta.
     */
    private SearchMeta ensureFreshData(JobSearchFilter filter, boolean forceRefresh) {
        SearchCacheService.CollectionState state = cacheService.stateOf(filter);

        boolean needsRefresh = forceRefresh
                || state.freshness() != SearchCacheService.Freshness.FRESH;
        if (needsRefresh) {
            refreshService.requestRefresh(filter, forceRefresh);
        }

        // A resposta descreve o que está sendo entregue: dado já coletado, e não uma coleta
        // feita agora. O cliente enxerga a idade real do acervo.
        //
        // `refreshing` existe para a interface poder dizer "atualizando vagas..." ao lado dos
        // resultados que já estão na tela, em vez de trocar tudo por um spinner: o que está
        // sendo entregue é válido, só não é o mais recente possível.
        SearchMeta meta = state.collectedAt() == null
                ? new SearchMeta(false, null, List.of(), List.of())
                : SearchMeta.cached(state.collectedAt());
        return meta.withRefreshing(needsRefresh);
    }

    /**
     * Página montada pelo banco: ordena, corta e conta lá.
     *
     * <p>Três consultas por página, todas pequenas: a página de ids já ordenada, a contagem
     * do total e os detalhes das vinte linhas com entity graph. A alternativa anterior
     * hidratava até {@code candidate-limit} vagas com empresa, fonte e tecnologias para
     * descartar quase tudo em seguida.
     *
     * <p>{@code LIMIT/OFFSET} e não keyset: medido em 50 mil vagas ativas, a página 250
     * (OFFSET 5000) responde em 1,2 ms por data e 4,6 ms por empresa. Keyset resolveria um
     * problema que este volume não tem, ao custo de cursor no contrato e perda das páginas
     * numeradas que a interface usa.
     */
    private PageResponse<JobSummaryResponse> paginateInDatabase(JobSearchFilter filter,
                                                                JobSearchRequest request,
                                                                int size,
                                                                Optional<ResumeProfile> profile,
                                                                SearchMeta meta) {
        var specification = JobSpecifications.ordered(filter, request.sortMode());
        Page<Job> page = jobRepository.findAll(specification,
                PageRequest.of(request.getPage(), size));

        List<Long> ids = page.getContent().stream().map(Job::getId).toList();
        List<JobSummaryResponse> content = ids.isEmpty()
                ? List.of()
                : toSummaries(ids, filter, profile);

        return new PageResponse<>(content, page.getNumber(), size, page.getTotalElements(),
                page.getTotalPages(), page.isLast(),
                // Nada foi truncado: a contagem é do conjunto inteiro, não de uma amostra.
                meta.withTruncated(false));
    }

    /**
     * Detalhes das vagas da página, na ordem que o banco decidiu.
     *
     * <p>O {@code IN} não preserva ordem, então ela é reimposta aqui a partir da lista de
     * ids — sobre vinte itens, custo irrelevante.
     */
    private List<JobSummaryResponse> toSummaries(List<Long> ids, JobSearchFilter filter,
                                                 Optional<ResumeProfile> profile) {
        Map<Long, Job> byId = jobRepository.findWithDetailsByIdIn(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Job::getId, job -> job));
        List<Job> ordered = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();

        Map<Long, CompatibilityResult> compatibility = profile
                .map(value -> recommendationService.scoreAll(value, ordered))
                .orElse(Map.of());

        return ordered.stream()
                .map(job -> {
                    // A relevância continua sendo informada: ela descreve a aderência ao
                    // filtro e a tela a exibe mesmo quando não é o critério de ordenação.
                    JobSummaryResponse summary = mapper.toSummary(job)
                            .withRelevance(scorer.score(job, filter));
                    CompatibilityResult result = compatibility.get(job.getId());
                    return result == null ? summary : summary.withCompatibility(result);
                })
                .toList();
    }

    /**
     * Sem {@code @Transactional}: {@code findWithDetailsByIdIn} usa entity graph e já traz
     * empresa, fonte e tecnologias, então nada é acessado de forma preguiçosa fora de transação.
     */
    private List<JobSummaryResponse> rank(JobSearchFilter filter,
                                          JobSearchRequest.SortMode sortMode,
                                          Optional<ResumeProfile> profile,
                                          boolean sortByCompatibility) {
        List<Long> ids = jobRepository.findCandidateIds(
                JobSpecifications.from(filter), properties.getCandidateLimit());
        if (ids.isEmpty()) {
            return List.of();
        }

        List<Job> jobs = jobRepository.findWithDetailsByIdIn(ids);
        Map<Long, Integer> relevance = jobs.stream()
                .collect(java.util.stream.Collectors.toMap(Job::getId, job -> scorer.score(job, filter)));
        Map<Long, CompatibilityResult> compatibility = profile
                .map(value -> recommendationService.scoreAll(value, jobs))
                .orElse(Map.of());

        Comparator<Job> comparator = sortByCompatibility && !compatibility.isEmpty()
                ? Comparator
                        .comparing((Job job) -> compatibility.get(job.getId()).score(),
                                Comparator.reverseOrder())
                        .thenComparing((Job job) -> relevance.get(job.getId()), Comparator.reverseOrder())
                : comparatorFor(sortMode, relevance);

        return jobs.stream()
                .sorted(comparator)
                .map(job -> {
                    JobSummaryResponse summary = mapper.toSummary(job)
                            .withRelevance(relevance.get(job.getId()));
                    CompatibilityResult result = compatibility.get(job.getId());
                    return result == null ? summary : summary.withCompatibility(result);
                })
                .toList();
    }

    private Comparator<Job> comparatorFor(JobSearchRequest.SortMode sortMode,
                                          Map<Long, Integer> relevance) {
        return switch (sortMode) {
            case RELEVANCE -> Comparator
                    .comparing((Job job) -> relevance.get(job.getId()), Comparator.reverseOrder())
                    .thenComparing(JobSearchService::publishedAtOrEpoch, Comparator.reverseOrder());
            case DATE -> Comparator
                    .comparing(JobSearchService::publishedAtOrEpoch, Comparator.reverseOrder());
            case COMPANY -> Comparator
                    .comparing((Job job) -> job.getCompany() == null ? "" : job.getCompany().getName(),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing((Job job) -> relevance.get(job.getId()), Comparator.reverseOrder());
        };
    }

    private static Instant publishedAtOrEpoch(Job job) {
        if (job.getPublishedAt() != null) {
            return job.getPublishedAt();
        }
        return job.getFirstSeenAt() == null ? Instant.EPOCH : job.getFirstSeenAt();
    }

    @Transactional(readOnly = true)
    public JobDetailsResponse findById(Long id, AuthenticatedUser current) {
        Job job = jobRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vaga", id));
        JobDetailsResponse details = mapper.toDetails(job);
        return recommendationService.profileFor(current)
                .map(profile -> details.withCompatibility(recommendationService.score(profile, job)))
                .orElse(details);
    }

    public List<JobScraper> scrapers() {
        return orchestrator.allScrapers();
    }
}
