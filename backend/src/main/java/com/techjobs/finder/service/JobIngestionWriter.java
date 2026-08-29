package com.techjobs.finder.service;

import com.techjobs.finder.entity.Company;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.JobRequirement;
import com.techjobs.finder.entity.JobSource;
import com.techjobs.finder.entity.RequirementKind;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.repository.CompanyRepository;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.repository.JobSourceRepository;
import com.techjobs.finder.repository.TechnologyRepository;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.service.JobNormalizer.Normalized;
import com.techjobs.finder.util.Text;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotNull;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gravação de um sub-lote de vagas, em transação própria.
 *
 * <p>Existe separado de {@link JobIngestionService} por causa da semântica transacional do
 * Spring: quando uma inserção viola a constraint única de {@code fingerprint}, a transação
 * corrente entra em <em>rollback-only</em> e nada mais pode ser gravado nela — um
 * {@code try/catch} em volta do laço não salvaria nada, apenas esconderia a falha até o
 * commit. Cada sub-lote precisa ser uma transação de verdade, iniciada por uma chamada
 * através do proxy, para que a falha de um não contamine os outros.
 *
 * <p>Todas as entidades são carregadas e gravadas dentro da mesma transação: nada de
 * objeto gerenciado atravessando fronteira de transação.
 */
@Service
public class JobIngestionWriter {

    private static final Logger log = LoggerFactory.getLogger(JobIngestionWriter.class);

    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final JobSourceRepository sourceRepository;
    private final TechnologyRepository technologyRepository;
    private final DeduplicationService deduplicationService;

    public JobIngestionWriter(JobRepository jobRepository,
                              CompanyRepository companyRepository,
                              JobSourceRepository sourceRepository,
                              TechnologyRepository technologyRepository,
                              DeduplicationService deduplicationService) {
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
        this.sourceRepository = sourceRepository;
        this.technologyRepository = technologyRepository;
        this.deduplicationService = deduplicationService;
    }

    /** Resultado da gravação de um sub-lote. */
    public record ChunkStats(int created, int updated, int skipped) {

        @Contract(" -> new")
        public static @NotNull @NonNull ChunkStats empty() {
            return new ChunkStats(0, 0, 0);
        }

        @Contract("_ -> new")
        public @NonNull ChunkStats plus(@NonNull ChunkStats other) {
            return new ChunkStats(created + other.created, updated + other.updated,
                    skipped + other.skipped);
        }
    }

    /**
     * Status da última coleta de cada fonte, em transação separada da gravação das vagas:
     * é informação de diagnóstico e não pode ser perdida porque um sub-lote falhou.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSourceStatus(@NonNull List<ScrapeResult> results) {
        Map<String, JobSource> sources = sourcesByCode();
        List<JobSource> touched = new ArrayList<>();
        for (ScrapeResult result : results) {
            JobSource source = sources.get(result.source());
            if (source == null) {
                continue;
            }
            source.setLastRunAt(Instant.now());
            source.setLastStatus(result.success() ? "OK" : "ERROR");
            source.setLastError(result.success() ? null : Text.truncate(result.errorMessage(), 1000));
            touched.add(source);
        }
        sourceRepository.saveAll(touched);
    }

    /**
     * Garante que toda empresa do sub-lote exista, em transação separada da gravação das
     * vagas.
     *
     * <p>Fica antes e fora do {@link #write}: se duas ingestões criarem a mesma empresa ao
     * mesmo tempo, o conflito se resolve aqui — repetindo esta chamada, a segunda tentativa
     * simplesmente encontra a linha que a outra acabou de gravar — sem arrastar junto o
     * lote de vagas.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureCompanies(@NonNull List<Normalized> chunk) {
        Map<String, String> displayNames = new HashMap<>();
        for (Normalized candidate : chunk) {
            String normalized = candidate.normalizedCompany();
            if (normalized != null && !normalized.isBlank()) {
                displayNames.putIfAbsent(normalized, candidate.company());
            }
        }
        if (displayNames.isEmpty()) {
            return;
        }

        Set<String> existing = companyRepository.findByNormalizedNameIn(displayNames.keySet())
                .stream()
                .map(Company::getNormalizedName)
                .collect(Collectors.toSet());

        List<Company> missing = displayNames.entrySet().stream()
                .filter(entry -> !existing.contains(entry.getKey()))
                .map(entry -> new Company(entry.getValue(), entry.getKey()))
                .toList();
        if (!missing.isEmpty()) {
            companyRepository.saveAll(missing);
        }
    }

    /**
     * Grava um sub-lote. Quatro consultas de leitura resolvem o lote inteiro — vagas já
     * conhecidas, fontes, empresas e vagas ativas dessas empresas — em vez de duas por
     * candidato.
     *
     * <p>Só grava vagas: as empresas já foram criadas por {@link #ensureCompanies}, de
     * modo que o único conflito possível aqui é o de {@code fingerprint}, e ele se resolve
     * relendo — na segunda tentativa a vaga existe e o caminho vira atualização.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException quando outra
     *         transação inseriu a mesma vaga em paralelo; quem chama reprocessa item a item
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChunkStats write(@NonNull List<Normalized> chunk) {
        if (chunk.isEmpty()) {
            return ChunkStats.empty();
        }

        Map<String, Job> knownJobs = jobsByFingerprint(chunk);
        Map<String, JobSource> sources = sourcesByCode();
        Map<String, Company> companies = companiesOf(chunk);
        Map<Long, List<Job>> activeByCompany = activeJobsOf(companies.values());
        Map<String, Technology> technologies = technologiesBySlug();

        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (Normalized candidate : chunk) {
            Job known = knownJobs.get(candidate.fingerprint());
            if (known != null) {
                touch(known, candidate);
                updated++;
                continue;
            }

            JobSource source = sources.get(candidate.raw().getSourceCode());
            if (source == null) {
                log.warn("Fonte '{}' não cadastrada em job_source; vaga ignorada",
                        candidate.raw().getSourceCode());
                skipped++;
                continue;
            }

            Company company = companies.get(candidate.normalizedCompany());
            if (company != null) {
                List<Job> sameCompany = activeByCompany.computeIfAbsent(company.getId(),
                        key -> new ArrayList<>());
                Optional<Job> duplicate =
                        deduplicationService.findExistingDuplicate(candidate, sameCompany);
                if (duplicate.isPresent()) {
                    touch(duplicate.get(), candidate);
                    updated++;
                    continue;
                }
            }

            Job job = jobRepository.save(toEntity(candidate, source, company, technologies));
            knownJobs.put(job.getFingerprint(), job);
            if (company != null) {
                // A vaga recém-criada passa a valer como duplicata em potencial para os
                // candidatos seguintes do mesmo sub-lote.
                activeByCompany.get(company.getId()).add(job);
            }
            created++;
        }

        return new ChunkStats(created, updated, skipped);
    }

    // ------------------------------------------------------------------ leituras em lote

    private @NonNull Map<String, Job> jobsByFingerprint(@NonNull List<Normalized> chunk) {
        Set<String> fingerprints = chunk.stream()
                .map(Normalized::fingerprint)
                .collect(Collectors.toSet());
        Map<String, Job> byFingerprint = new HashMap<>(fingerprints.size());
        for (Job job : jobRepository.findByFingerprintIn(fingerprints)) {
            byFingerprint.put(job.getFingerprint(), job);
        }
        return byFingerprint;
    }

    /** São poucas linhas (uma por fonte cadastrada): uma consulta cobre qualquer lote. */
    private Map<String, JobSource> sourcesByCode() {
        return sourceRepository.findAll().stream()
                .collect(Collectors.toMap(JobSource::getCode, Function.identity()));
    }

    private Map<String, Technology> technologiesBySlug() {
        return technologyRepository.findAll().stream()
                .collect(Collectors.toMap(Technology::getSlug, Function.identity()));
    }

    /** Empresas do sub-lote, já criadas por {@link #ensureCompanies}. */
    private Map<String, Company> companiesOf(@NonNull List<Normalized> chunk) {//Empresas que já foram criadas
        Set<String> names = new LinkedHashSet<>();
        for (Normalized candidate : chunk) {
            String normalized = candidate.normalizedCompany();
            if (normalized != null && !normalized.isBlank()) {
                names.add(normalized);
            }
        }
        if (names.isEmpty()) {
            return Map.of();
        }
        return companyRepository.findByNormalizedNameIn(names).stream()
                .collect(Collectors.toMap(Company::getNormalizedName, Function.identity()));
    }

    private @NonNull Map<Long, List<Job>> activeJobsOf(java.util.@NonNull Collection<Company> companies) {
        Set<Long> ids = companies.stream()
                .map(Company::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, List<Job>> grouped = new HashMap<>();
        for (Job job : jobRepository.findActiveByCompanyIdIn(ids)) {
            grouped.computeIfAbsent(job.getCompany().getId(), key -> new ArrayList<>()).add(job);
        }
        return grouped;
    }

    // ------------------------------------------------------------------ escrita

    private Job toEntity(Normalized candidate,
                         JobSource source,
                         Company company,
                         Map<String, Technology> technologyIndex) {
        Job job = new Job();
        job.setSource(source);
        job.setCompany(company);
        job.setExternalId(Text.truncate(candidate.raw().getExternalId(), 255));
        job.setFingerprint(candidate.fingerprint());
        job.setTitle(candidate.title());
        job.setNormalizedTitle(candidate.normalizedTitle());
        job.setLocation(candidate.location());
        job.setCountry(candidate.country());
        job.setCountryCode(candidate.countryCode());
        job.setWorkModel(candidate.workModel());
        job.setExperienceLevel(candidate.experienceLevel());
        job.setSummary(candidate.summary());
        job.setDescription(candidate.description());
        job.setShortDescription(candidate.shortDescription());
        job.setUrl(Text.truncate(candidate.raw().getUrl(), 1000));
        job.setSalaryRaw(candidate.salary());
        applySalary(job, candidate);
        job.setExperienceYears(candidate.experienceYears());
        job.setBenefits(candidate.benefits());
        job.setPublishedAt(candidate.raw().getPublishedAt());
        job.setSourceUpdatedAt(candidate.raw().getUpdatedAt());
        job.setTechnologies(resolveTechnologies(candidate.technologySlugs(), technologyIndex));
        job.replaceRequirements(toRequirements(candidate));
        return job;
    }

    /** Requisitos e diferenciais na ordem do anúncio, prontos para a tela de detalhes. */
    private List<JobRequirement> toRequirements(Normalized candidate) {
        List<JobRequirement> items = new ArrayList<>();
        List<String> required = candidate.requirements();
        for (int i = 0; i < required.size(); i++) {
            items.add(new JobRequirement(RequirementKind.REQUIRED, i, required.get(i)));
        }
        List<String> nice = candidate.niceToHave();
        for (int i = 0; i < nice.size(); i++) {
            items.add(new JobRequirement(RequirementKind.NICE_TO_HAVE, i, nice.get(i)));
        }
        return items;
    }

    private void applySalary(Job job, Normalized candidate) {
        var salary = candidate.structuredSalary();
        if (salary == null || salary.isEmpty()) {
            return;
        }
        job.setSalaryMin(salary.min());
        job.setSalaryMax(salary.max());
        job.setSalaryCurrency(salary.currency());
        job.setSalaryPeriod(salary.period());
    }

    private Set<Technology> resolveTechnologies(Set<String> slugs, Map<String, Technology> index) {
        Set<Technology> result = new LinkedHashSet<>();
        for (String slug : slugs) {
            Technology technology = index.get(slug);
            if (technology != null) {
                result.add(technology);
            }
        }
        return result;
    }

    /** Vaga já existente: renova o "visto por último" e atualiza o que costuma mudar. */
    private void touch(Job job, Normalized candidate) {
        job.setLastSeenAt(Instant.now());
        job.setActive(true);
        if (candidate.salary() != null) {
            job.setSalaryRaw(candidate.salary());
            applySalary(job, candidate);
        }
        // Fontes diferentes recortam o anúncio de formas diferentes: fica a versão mais
        // completa, que é a que serve para o usuário decidir.
        if (isLonger(candidate.description(), job.getDescription())) {
            job.setDescription(candidate.description());
            job.setShortDescription(candidate.shortDescription());
            if (!candidate.requirements().isEmpty() || !candidate.niceToHave().isEmpty()) {
                job.replaceRequirements(toRequirements(candidate));
            }
        }
        if (candidate.experienceYears() != null) {
            job.setExperienceYears(candidate.experienceYears());
        }
        // O país só melhora: outra fonte pode dizer "São Paulo, Brasil" onde a primeira
        // dizia só "Remote". O caminho inverso — trocar um país conhecido por ZZ — apagaria
        // informação boa por causa de uma fonte mais pobre.
        if (CountryCatalog.GLOBAL.equals(job.getCountryCode())) {
            job.setCountryCode(candidate.countryCode());
        }
        // O nível pode melhorar quando outra fonte traz uma descrição mais completa.
        if (job.getExperienceLevel() == ExperienceLevel.UNKNOWN
                && candidate.experienceLevel() != ExperienceLevel.UNKNOWN) {
            job.setExperienceLevel(candidate.experienceLevel());
        }
        if (candidate.raw().getUpdatedAt() != null) {
            job.setSourceUpdatedAt(candidate.raw().getUpdatedAt());
        }
        if (job.getPublishedAt() == null && candidate.raw().getPublishedAt() != null) {
            job.setPublishedAt(candidate.raw().getPublishedAt());
        }
        jobRepository.save(job);
    }

    private boolean isLonger(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return current == null || candidate.length() > current.length();
    }
}
