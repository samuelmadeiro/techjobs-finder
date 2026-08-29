package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.config.CacheProperties;
import com.techjobs.finder.entity.JobSource;
import com.techjobs.finder.entity.TechnologyKind;
import com.techjobs.finder.mapper.JobMapper;
import com.techjobs.finder.repository.CompanyRepository;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.repository.JobSourceRepository;
import com.techjobs.finder.repository.TechnologyRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Comportamento de cache do catálogo, independente do provedor.
 *
 * <p>Roda sobre o cache em memória: o que está sob teste é o contrato — resultado
 * reaproveitado, invalidação funcionando e falha de cache que não derruba a chamada —
 * e não a implementação do Redis, que é responsabilidade da biblioteca.
 */
@SpringBootTest(classes = {CatalogService.class, CacheConfig.class, CacheProperties.class,
        TechnologyCatalog.class, CountryCatalog.class, JobMapper.class})
class CatalogCacheTest {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private TechnologyRepository technologyRepository;

    @MockitoBean
    private CompanyRepository companyRepository;

    @MockitoBean
    private JobSourceRepository sourceRepository;

    /** O catálogo de países pergunta ao banco quantas vagas cada um tem. */
    @MockitoBean
    private JobRepository jobRepository;

    /**
     * O contexto Spring é compartilhado pelos métodos de teste, e com ele o cache.
     * Sem limpar, um teste enxergaria a entrada gravada pelo anterior e passaria por
     * motivo errado.
     */
    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames()
                .forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("a segunda chamada é servida do cache, sem novo acesso ao banco")
    void reusesCachedCatalog() {
        given(technologyRepository.findWithActiveJobCountByKind(TechnologyKind.LANGUAGE))
                .willReturn(List.of(technologyCount("java", "Java", TechnologyKind.LANGUAGE, 42L)));

        var first = catalogService.languages();
        var second = catalogService.languages();

        assertThat(first).isEqualTo(second);
        verify(technologyRepository, times(1)).findWithActiveJobCountByKind(TechnologyKind.LANGUAGE);
    }

    @Test
    @DisplayName("limpar o cache faz a próxima chamada consultar o banco de novo")
    void evictionForcesReload() {
        given(technologyRepository.findWithActiveJobCount())
                .willReturn(List.of(technologyCount("docker", "Docker", TechnologyKind.TOOL, 7L)));

        catalogService.technologies();
        cacheManager.getCache(CacheConfig.CATALOG_CACHE).clear();
        catalogService.technologies();

        verify(technologyRepository, times(2)).findWithActiveJobCount();
    }

    @Test
    @DisplayName("cada catálogo tem a própria entrada; um não sobrepõe o outro")
    void keysAreIndependent() {
        given(technologyRepository.findWithActiveJobCountByKind(TechnologyKind.LANGUAGE))
                .willReturn(List.of(technologyCount("go", "Go", TechnologyKind.LANGUAGE, 5L)));
        given(sourceRepository.findAll())
                .willReturn(List.of(new JobSource("remotive", "Remotive", "https://remotive.com")));

        assertThat(catalogService.languages()).isNotEmpty();
        assertThat(catalogService.sources()).hasSize(1);

        verify(technologyRepository, times(1)).findWithActiveJobCountByKind(TechnologyKind.LANGUAGE);
        verify(sourceRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("falha no cache não vira erro: a chamada segue para a origem")
    void cacheFailureFallsBackToSource() {
        var handler = new CacheConfig().errorHandler();
        var cache = cacheManager.getCache(CacheConfig.CATALOG_CACHE);

        // O contrato do tratador é justamente não relançar; se relançasse, um Redis
        // fora do ar viraria 500 numa requisição que o banco atenderia.
        handler.handleCacheGetError(new RuntimeException("redis fora"), cache, "languages");
        handler.handleCachePutError(new RuntimeException("redis fora"), cache, "languages", List.of());
        handler.handleCacheEvictError(new RuntimeException("redis fora"), cache, "languages");
        handler.handleCacheClearError(new RuntimeException("redis fora"), cache);
    }

    @Test
    @DisplayName("erro do repositório não fica gravado no cache")
    void failureIsNotCached() {
        // Primeira chamada falha, segunda funciona: se o cache tivesse guardado o
        // erro (ou um vazio), a segunda continuaria quebrada até o TTL vencer.
        given(companyRepository.findActiveCompaniesWithJobCount(any()))
                .willThrow(new IllegalStateException("banco fora"))
                .willReturn(List.of(companyCount(1L, "Empresa XYZ", 3L)));

        assertThatThrownBy(() -> catalogService.companies())
                .isInstanceOf(IllegalStateException.class);

        assertThat(catalogService.companies()).hasSize(1);
    }

    /** Projeções são interfaces: o teste fornece a implementação mínima que o serviço lê. */
    private static TechnologyRepository.TechnologyJobCount technologyCount(
            String slug, String name, TechnologyKind kind, long jobCount) {
        return new TechnologyRepository.TechnologyJobCount() {
            @Override
            public String getSlug() {
                return slug;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public TechnologyKind getKind() {
                return kind;
            }

            @Override
            public long getJobCount() {
                return jobCount;
            }
        };
    }

    private static CompanyRepository.CompanyJobCount companyCount(Long id, String name, long jobCount) {
        return new CompanyRepository.CompanyJobCount() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public long getJobCount() {
                return jobCount;
            }
        };
    }
}
