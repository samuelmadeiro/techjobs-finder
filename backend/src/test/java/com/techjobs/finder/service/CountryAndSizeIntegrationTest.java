package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.dto.PageResponse;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.dto.job.JobSummaryResponse;
import com.techjobs.finder.repository.JobRepository;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.scraper.ScraperOrchestrator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Filtro de país e quantidade, do parâmetro HTTP até a linha do banco.
 *
 * <p>O orquestrador entra como mock por um motivo só: provar que escolher um país inédito
 * não faz a busca esperar coleta. É o risco central desta funcionalidade — {@code country=US}
 * nunca pesquisado é, para o cache, exatamente o mesmo caso de um filtro novo qualquer.
 */
@AutoConfigureMockMvc
class CountryAndSizeIntegrationTest extends PostgresIntegrationTest {

    private static final CountryCatalog COUNTRIES = new CountryCatalog();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobSearchService searchService;

    @Autowired
    private JobIngestionService ingestionService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TechnologySeedService technologySeedService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        jdbc.update("DELETE FROM search_cache_entry");
        jdbc.update("DELETE FROM scraping_job");
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
        cacheManager.getCache(CacheConfig.CATALOG_CACHE).clear();
        technologySeedService.seed();

        List<RawJob> jobs = new ArrayList<>();
        // 12 do Brasil, 8 dos EUA, 5 de Portugal e 30 sem país definido (remoto global).
        jobs.addAll(batch("br", 12, "São Paulo, Brasil"));
        jobs.addAll(batch("us", 8, "Austin, United States"));
        jobs.addAll(batch("pt", 5, "Lisboa, Portugal"));
        jobs.addAll(batch("global", 30, "Anywhere"));
        ingestionService.ingest(List.of(ScrapeResult.success("remoteok", jobs, Duration.ZERO)));
        cacheManager.getCache(CacheConfig.SEARCH_CACHE).clear();
    }

    private static List<RawJob> batch(String prefix, int count, String location) {
        List<RawJob> jobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            jobs.add(new RawJob()
                    .setTitle("Pessoa Desenvolvedora Java " + prefix + " " + i)
                    .setCompany("Empresa " + prefix + " " + i)
                    .setLocation(location)
                    .setDescriptionHtml("<p>Java e Spring Boot</p>")
                    .setUrl("https://pais.test/" + prefix + "/" + i)
                    .setWorkModelHint("remote")
                    .setPublishedAt(Instant.now().minusSeconds(i * 60L))
                    .setSourceCode("remoteok"));
        }
        return jobs;
    }

    private JobSearchRequest request(String country, int size) {
        JobSearchRequest request = new JobSearchRequest();
        request.setSort("date");
        request.setCountry(country);
        request.setSize(size);
        return request;
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    // ------------------------------------------------------------------ classificação

    @Test
    @DisplayName("a ingestão grava o código de país de cada vaga")
    void ingestionClassifiesCountry() {
        assertThat(countOf("BR")).isEqualTo(12);
        assertThat(countOf("US")).isEqualTo(8);
        assertThat(countOf("PT")).isEqualTo(5);
        // "Anywhere" não é país nenhum: vai para o balde global.
        assertThat(countOf(CountryCatalog.GLOBAL)).isEqualTo(30);
    }

    private Integer countOf(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM job WHERE country_code = ?",
                Integer.class, code);
    }

    @Test
    @DisplayName("sigla de duas letras dentro de palavra não vira país")
    void twoLetterCodesInsideWordsAreIgnored() {
        // "Austin" contém "us", "Rio DE Janeiro" contém "de": nenhum dos dois decide país
        // por sigla — o que vale é o nome do país ou a cidade conhecida.
        assertThat(COUNTRIES.classify("Austin", null)).isEqualTo("US");
        assertThat(COUNTRIES.classify("Rio de Janeiro", null)).isEqualTo("BR");
        assertThat(COUNTRIES.classify("Brighton", null)).isEqualTo(CountryCatalog.GLOBAL);
        assertThat(COUNTRIES.classify("LATAM", null)).isEqualTo(CountryCatalog.GLOBAL);
        assertThat(COUNTRIES.classify("Europe", null)).isEqualTo(CountryCatalog.GLOBAL);
        assertThat(COUNTRIES.classify(null, null)).isEqualTo(CountryCatalog.GLOBAL);
    }

    // ------------------------------------------------------------------ busca por país

    @Test
    @DisplayName("busca no Brasil traz as vagas do Brasil e as sem país definido")
    void searchBrazil() {
        PageResponse<JobSummaryResponse> page = searchService.search(request("BR", 100), null);

        assertThat(page.totalElements()).isEqualTo(12 + 30);
        assertThat(page.content()).noneMatch(job -> job.location().contains("United States"));
        assertThat(page.content()).noneMatch(job -> job.location().contains("Portugal"));
    }

    @Test
    @DisplayName("busca nos Estados Unidos não traz vaga exclusiva do Brasil")
    void searchUnitedStates() {
        PageResponse<JobSummaryResponse> page = searchService.search(request("US", 100), null);

        assertThat(page.totalElements()).isEqualTo(8 + 30);
        assertThat(page.content()).noneMatch(job -> job.location().contains("Brasil"));
    }

    @Test
    @DisplayName("busca em Portugal traz as vagas de Portugal e as globais")
    void searchPortugal() {
        PageResponse<JobSummaryResponse> page = searchService.search(request("PT", 100), null);

        assertThat(page.totalElements()).isEqualTo(5 + 30);
        assertThat(page.content()).noneMatch(job -> job.location().contains("Austin"));
    }

    @Test
    @DisplayName("sem país, a busca traz o acervo inteiro")
    void searchWithoutCountry() {
        PageResponse<JobSummaryResponse> page = searchService.search(request(null, 100), null);

        assertThat(page.totalElements()).isEqualTo(12 + 8 + 5 + 30);
    }

    @Test
    @DisplayName("dois países diferentes não compartilham resultado nem entrada de cache")
    void differentCountriesDoNotShareResults() throws Exception {
        MvcResult brasil = mockMvc.perform(get("/api/jobs?country=BR&size=100&sort=date"))
                .andExpect(status().isOk()).andReturn();
        MvcResult eua = mockMvc.perform(get("/api/jobs?country=US&size=100&sort=date"))
                .andExpect(status().isOk()).andReturn();

        List<String> brasilTitles = titles(dataOf(brasil));
        List<String> euaTitles = titles(dataOf(eua));

        assertThat(brasilTitles).anyMatch(title -> title.contains("java br"));
        assertThat(brasilTitles).noneMatch(title -> title.contains("java us"));
        assertThat(euaTitles).anyMatch(title -> title.contains("java us"));
        assertThat(euaTitles).noneMatch(title -> title.contains("java br"));
    }

    private static List<String> titles(JsonNode data) {
        List<String> titles = new ArrayList<>();
        data.get("content").forEach(job -> titles.add(job.get("title").asText().toLowerCase()));
        return titles;
    }

    // ------------------------------------------------------------------ fingerprint e cache

    @Test
    @DisplayName("país diferente produz fingerprint diferente; mesmo país, o mesmo")
    void countryChangesFingerprint() {
        String brasil = request("BR", 20).toFilter(COUNTRIES).fingerprint();
        String eua = request("US", 20).toFilter(COUNTRIES).fingerprint();
        String semPais = request(null, 20).toFilter(COUNTRIES).fingerprint();

        assertThat(brasil).isNotEqualTo(eua).isNotEqualTo(semPais);
        assertThat(request("br", 20).toFilter(COUNTRIES).fingerprint()).isEqualTo(brasil);
    }

    @Test
    @DisplayName("cada país enfileira a sua própria coleta")
    void eachCountryEnqueuesItsOwnCollection() {
        searchService.search(request("BR", 20), null);
        searchService.search(request("US", 20), null);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE status IN ('QUEUED','RUNNING')",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("quantidade diferente não reaproveita a página de outra quantidade")
    void sizeIsPartOfTheResponseCacheKey() {
        PageResponse<JobSummaryResponse> dez = searchService.search(request("BR", 10), null);
        PageResponse<JobSummaryResponse> vinte = searchService.search(request("BR", 20), null);

        assertThat(dez.content()).hasSize(10);
        assertThat(vinte.content()).hasSize(20);
        assertThat(dez.size()).isEqualTo(10);
        assertThat(vinte.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("busca repetida do mesmo país e tamanho é servida do cache")
    void repeatedSearchHitsCache() {
        PageResponse<JobSummaryResponse> primeira = searchService.search(request("BR", 20), null);
        PageResponse<JobSummaryResponse> segunda = searchService.search(request("BR", 20), null);

        assertThat(segunda.content()).hasSameSizeAs(primeira.content());
        assertThat(segunda.totalElements()).isEqualTo(primeira.totalElements());
        // Nenhuma coleta nova: a combinação já está reservada.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE status IN ('QUEUED','RUNNING')",
                Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ quantidade

    @Test
    @DisplayName("size 10, 20 e 50 devolvem exatamente o pedido quando há vagas de sobra")
    void sizeIsRespected() {
        assertThat(searchService.search(request(null, 10), null).content()).hasSize(10);
        assertThat(searchService.search(request(null, 20), null).content()).hasSize(20);
        assertThat(searchService.search(request(null, 50), null).content()).hasSize(50);
    }

    @Test
    @DisplayName("com menos vagas que o pedido, devolve o que existe — sem completar")
    void fewerResultsThanRequested() {
        // Portugal tem 5 próprias e 30 globais: 35 no total, menos que as 100 pedidas.
        PageResponse<JobSummaryResponse> page = searchService.search(request("PT", 100), null);

        assertThat(page.content()).hasSize(35);
        assertThat(page.totalElements()).isEqualTo(35);
    }

    @Test
    @DisplayName("size acima do teto é recusado com o erro padrão da API")
    void oversizedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/jobs?size=999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/api/jobs?size=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/jobs?size=-1")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size 100 é aceito: é o maior valor oferecido pela interface")
    void maximumSizeIsAccepted() throws Exception {
        mockMvc.perform(get("/api/jobs?size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    // ------------------------------------------------------------------ validação de país

    @Test
    @DisplayName("país inexistente é recusado com o erro padrão da API")
    void invalidCountryIsRejected() throws Exception {
        mockMvc.perform(get("/api/jobs?country=XYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/jobs?country=ZZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("country"));

        // Nome por extenso também não: o contrato é o código.
        mockMvc.perform(get("/api/jobs?country=Brasil"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ catálogo

    @Test
    @DisplayName("GET /api/countries lista os países com código, nome, bandeira e contagem")
    void countryCatalogIsPublished() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").exists())
                .andReturn();

        JsonNode data = dataOf(result);
        assertThat(data).hasSize(COUNTRIES.all().size());
        JsonNode brasil = data.get(0);
        assertThat(brasil.get("code").asText()).isEqualTo("BR");
        assertThat(brasil.get("name").asText()).isEqualTo("Brasil");
        assertThat(brasil.get("flag").asText()).isNotBlank();
        // A contagem é a mesma que a busca daquele país devolve: próprias mais globais.
        assertThat(brasil.get("jobCount").asLong()).isEqualTo(12 + 30);
    }

    // ------------------------------------------------------------------ desempenho

    @Test
    @DisplayName("país nunca pesquisado responde na hora e só enfileira a coleta")
    void unknownCountryDoesNotBlockOnScraping() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/jobs?country=CA&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        // Nenhuma fonte foi acionada dentro da requisição.
        verifyNoInteractions(orchestrator);
        // E a coleta daquele país ficou enfileirada para o worker.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraping_job WHERE status = 'QUEUED'", Integer.class))
                .isEqualTo(1);
        // A resposta avisa que há atualização a caminho, sem esconder o que já existe.
        assertThat(dataOf(result).get("meta").get("refreshing").asBoolean()).isTrue();
        assertThat(dataOf(result).get("content")).isNotEmpty();
    }

    @Test
    @DisplayName("deduplicação continua valendo com país: a mesma vaga não entra duas vezes")
    void deduplicationStillWorks() {
        long before = jobRepository.count();

        // Mesmíssimo lote de novo, agora por outra fonte: é a mesma vaga.
        ingestionService.ingest(List.of(ScrapeResult.success("himalayas",
                batch("br", 12, "São Paulo, Brasil"), Duration.ZERO)));

        assertThat(jobRepository.count()).isEqualTo(before);
        assertThat(countOf("BR")).isEqualTo(12);
    }
}
