package com.techjobs.finder.service;

import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.dto.CatalogDtos;
import com.techjobs.finder.entity.TechnologyKind;
import com.techjobs.finder.mapper.JobMapper;
import com.techjobs.finder.repository.CompanyRepository;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.repository.JobSourceRepository;
import com.techjobs.finder.repository.TechnologyRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Endpoints de apoio ao frontend: linguagens, tecnologias, empresas e fontes.
 * Resultados ficam em cache curto porque mudam pouco e são pedidos a cada carga de página.
 */
@Service
public class CatalogService {

    private final TechnologyRepository technologyRepository;
    private final CompanyRepository companyRepository;
    private final JobSourceRepository sourceRepository;
    private final JobRepository jobRepository;
    private final TechnologyCatalog catalog;
    private final CountryCatalog countryCatalog;
    private final JobMapper mapper;

    public CatalogService(TechnologyRepository technologyRepository,
                          CompanyRepository companyRepository,
                          JobSourceRepository sourceRepository,
                          JobRepository jobRepository,
                          TechnologyCatalog catalog,
                          CountryCatalog countryCatalog,
                          JobMapper mapper) {
        this.technologyRepository = technologyRepository;
        this.companyRepository = companyRepository;
        this.sourceRepository = sourceRepository;
        this.jobRepository = jobRepository;
        this.catalog = catalog;
        this.countryCatalog = countryCatalog;
        this.mapper = mapper;
    }

    @Cacheable(cacheNames = CacheConfig.CATALOG_CACHE, key = "'languages'")
    @Transactional(readOnly = true)
    public List<CatalogDtos.TechnologyResponse> languages() {
        return merge(catalog.byKind(TechnologyKind.LANGUAGE),
                technologyRepository.findWithActiveJobCountByKind(TechnologyKind.LANGUAGE));
    }

    @Cacheable(cacheNames = CacheConfig.CATALOG_CACHE, key = "'technologies'")
    @Transactional(readOnly = true)
    public List<CatalogDtos.TechnologyResponse> technologies() {
        List<TechnologyCatalog.Entry> entries = catalog.all().stream()
                .filter(entry -> entry.kind() != TechnologyKind.LANGUAGE)
                .toList();
        return merge(entries, technologyRepository.findWithActiveJobCount());
    }

    /**
     * O catálogo em código é a fonte da verdade da lista; o banco só acrescenta
     * quantas vagas ativas existem para cada item.
     */
    private List<CatalogDtos.TechnologyResponse> merge(
            List<TechnologyCatalog.Entry> entries,
            List<TechnologyRepository.TechnologyJobCount> counts) {
        Map<String, Long> countBySlug = new LinkedHashMap<>();
        for (TechnologyRepository.TechnologyJobCount row : counts) {
            countBySlug.put(row.getSlug(), row.getJobCount());
        }
        List<CatalogDtos.TechnologyResponse> result = new ArrayList<>();
        for (TechnologyCatalog.Entry entry : entries) {
            result.add(new CatalogDtos.TechnologyResponse(entry.slug(), entry.name(), entry.kind(),
                    countBySlug.getOrDefault(entry.slug(), 0L)));
        }
        result.sort((a, b) -> {
            int byCount = Long.compare(b.jobCount(), a.jobCount());
            return byCount != 0 ? byCount : a.name().compareToIgnoreCase(b.name());
        });
        return result;
    }

    @Cacheable(cacheNames = CacheConfig.CATALOG_CACHE, key = "'companies'")
    @Transactional(readOnly = true)
    public List<CatalogDtos.CompanyResponse> companies() {
        return companyRepository.findActiveCompaniesWithJobCount(Limit.of(100)).stream()
                .map(row -> new CatalogDtos.CompanyResponse(row.getId(), row.getName(), row.getJobCount()))
                .toList();
    }

    /**
     * Países oferecidos no filtro, com quantas vagas ativas cada um tem hoje.
     *
     * <p>A lista vem do {@link CountryCatalog}, não do banco: um país precisa aparecer no
     * seletor mesmo quando ainda não há vaga coletada dele — é justamente escolhendo o país
     * que o usuário faz a primeira coleta acontecer. O banco só acrescenta a contagem.
     *
     * <p>A contagem soma o país e o balde sem país definido (ZZ), porque é esse o conjunto
     * que a busca daquele país devolve. Somar só o código exato mostraria "Brasil (3)" numa
     * busca que traz trezentas vagas remotas elegíveis.
     */
    @Cacheable(cacheNames = CacheConfig.CATALOG_CACHE, key = "'countries'")
    @Transactional(readOnly = true)
    public List<CatalogDtos.CountryResponse> countries() {
        Map<String, Long> byCode = new LinkedHashMap<>();
        for (JobRepository.CountryJobCount row : jobRepository.countActiveByCountry()) {
            byCode.put(row.getCode(), row.getJobCount());
        }
        long global = byCode.getOrDefault(CountryCatalog.GLOBAL, 0L);
        return countryCatalog.all().stream()
                .map(country -> new CatalogDtos.CountryResponse(country.code(), country.name(),
                        country.flag(), byCode.getOrDefault(country.code(), 0L) + global))
                .toList();
    }

    /** TTL curto: o status da última coleta muda a cada rodada do scheduler. */
    @Cacheable(cacheNames = CacheConfig.SOURCES_CACHE, key = "'all'")
    @Transactional(readOnly = true)
    public List<CatalogDtos.SourceResponse> sources() {
        return sourceRepository.findAll().stream().map(mapper::toResponse).toList();
    }
}
