package com.techjobs.finder.dto.job;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.exception.InvalidFilterException;
import com.techjobs.finder.service.CountryCatalog;
import com.techjobs.finder.util.Slugs;
import com.techjobs.finder.util.Text;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parâmetros crus da query string. Todos são opcionais e combináveis.
 * A conversão para {@link JobSearchFilter} valida e normaliza os valores.
 */
public class JobSearchRequest {

    /** Aceita repetição (?language=java&language=go) ou lista separada por vírgula. */
    private List<@Size(max = 60) String> language = new ArrayList<>();

    private List<@Size(max = 60) String> technology = new ArrayList<>();

    @Size(max = 30)
    private String level;

    @Size(max = 30)
    private String workModel;

    /**
     * País desejado em código ISO-3166 alpha-2 ({@code BR}, {@code US}, ...). Vazio = todos.
     *
     * <p>Código e não nome: "Brasil", "Brazil" e "brasil" seriam três buscas diferentes para
     * o cache e três chaves diferentes no fingerprint, para a mesma intenção. A lista de
     * códigos válidos vem do {@code CountryCatalog} e é a mesma que o frontend recebe em
     * {@code GET /api/countries}.
     */
    @Size(max = 2)
    private String country;

    @Size(max = 120)
    private String location;

    @Size(max = 200)
    private String keyword;

    private List<@Size(max = 60) String> source = new ArrayList<>();

    @Min(0)
    private int page = 0;

    /**
     * Quantidade de vagas por resposta. É o "quantas vagas quero receber" da interface —
     * não existe um segundo parâmetro de quantidade, porque este já significa exatamente
     * isso e já faz parte da chave do cache de páginas.
     *
     * <p>O teto de 100 é o mesmo de {@code techjobs.search.max-page-size}, e o serviço
     * ainda o reaplica: um cliente que mande 999 leva 400, e um cliente que burle a
     * validação continua limitado no serviço.
     */
    @Min(1)
    @Max(100)
    private int size = 20;

    /** {@code relevance} (padrão), {@code date} ou {@code company}. */
    @Size(max = 20)
    private String sort = "relevance";

    /** Força nova coleta ignorando o TTL do cache. */
    private boolean refresh = false;

    /**
     * @param countries catálogo que decide quais códigos de país existem. Entra como
     *                  parâmetro para a conversão continuar sendo o único lugar que
     *                  interpreta parâmetro cru — sem espalhar validação de país pelo
     *                  controller e pelo serviço.
     */
    public JobSearchFilter toFilter(CountryCatalog countries) {
        return new JobSearchFilter(
                Slugs.normalizeAll(language),
                Slugs.normalizeAll(technology),
                parseLevel(),
                parseWorkModel(),
                parseCountry(countries),
                Text.blankToNull(location),
                Text.blankToNull(keyword),
                Slugs.normalizeAll(source));
    }

    private String parseCountry(CountryCatalog countries) {
        String value = Text.blankToNull(country);
        if (value == null) {
            return null;
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (!countries.isSupported(code)) {
            throw new InvalidFilterException("country", country,
                    "valores aceitos: " + countries.supportedCodes());
        }
        return code;
    }

    private ExperienceLevel parseLevel() {
        if (Text.blankToNull(level) == null) {
            return null;
        }
        ExperienceLevel parsed = ExperienceLevel.from(level);
        if (parsed == ExperienceLevel.UNKNOWN) {
            throw new InvalidFilterException("level", level,
                    "valores aceitos: INTERNSHIP, TRAINEE, JUNIOR, MID, SENIOR, ALL");
        }
        return parsed;
    }

    private WorkModel parseWorkModel() {
        if (Text.blankToNull(workModel) == null) {
            return null;
        }
        WorkModel parsed = WorkModel.from(workModel);
        if (parsed == WorkModel.UNKNOWN) {
            throw new InvalidFilterException("workModel", workModel,
                    "valores aceitos: REMOTE, HYBRID, ONSITE, ALL");
        }
        return parsed;
    }

    public SortMode sortMode() {
        String value = Text.blankToNull(sort) == null ? "relevance" : sort.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "relevance", "relevancia", "relevância" -> SortMode.RELEVANCE;
            case "date", "data", "recent" -> SortMode.DATE;
            case "company", "empresa" -> SortMode.COMPANY;
            default -> throw new InvalidFilterException("sort", sort, "valores aceitos: relevance, date, company");
        };
    }

    public enum SortMode {
        RELEVANCE,
        DATE,
        COMPANY
    }

    public List<String> getLanguage() {
        return language;
    }

    public void setLanguage(List<String> language) {
        this.language = language;
    }

    public List<String> getTechnology() {
        return technology;
    }

    public void setTechnology(List<String> technology) {
        this.technology = technology;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getWorkModel() {
        return workModel;
    }

    public void setWorkModel(String workModel) {
        this.workModel = workModel;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public List<String> getSource() {
        return source;
    }

    public void setSource(List<String> source) {
        this.source = source;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public boolean isRefresh() {
        return refresh;
    }

    public void setRefresh(boolean refresh) {
        this.refresh = refresh;
    }
}
