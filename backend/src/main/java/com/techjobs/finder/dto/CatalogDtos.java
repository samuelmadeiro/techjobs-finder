package com.techjobs.finder.dto;

import com.techjobs.finder.entity.TechnologyKind;
import java.time.Instant;

/** DTOs pequenos dos endpoints de catálogo. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record TechnologyResponse(String slug, String name, TechnologyKind kind, long jobCount) {
    }

    public record CompanyResponse(Long id, String name, long jobCount) {
    }

    /**
     * País oferecido no filtro.
     *
     * <p>{@code code} é o que volta na query string; {@code name} e {@code flag} existem
     * para a interface não manter a sua própria tabela de países — se um país entrar ou
     * sair, ninguém precisa mexer no frontend. {@code jobCount} conta as vagas ativas
     * daquele país mais as sem país definido, que é exatamente o conjunto que a busca
     * daquele país devolve.
     */
    public record CountryResponse(String code, String name, String flag, long jobCount) {
    }

    public record SourceResponse(
            String code,
            String name,
            String baseUrl,
            boolean enabled,
            Instant lastRunAt,
            String lastStatus,
            String lastError) {
    }
}
