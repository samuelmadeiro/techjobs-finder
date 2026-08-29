package com.techjobs.finder.dto.job;

import java.time.Instant;
import java.util.List;

/**
 * Contexto da busca devolvido junto dos resultados: se veio do cache, quais fontes
 * responderam e quais falharam. O frontend usa isso para avisar o usuário sem quebrar a página.
 */
public record SearchMeta(
        boolean fromCache,
        Instant collectedAt,
        List<String> sourcesQueried,
        List<SourceFailure> failures,
        /* true quando a busca bateu no teto de candidatos: o total é um piso, não o número exato. */
        boolean truncated,
        /* true quando há coleta enfileirada para estes filtros: os resultados já servem, e
           uma versão mais recente está a caminho. */
        boolean refreshing) {

    public record SourceFailure(String source, String message) {
    }

    public SearchMeta(boolean fromCache, Instant collectedAt, List<String> sourcesQueried,
                      List<SourceFailure> failures) {
        this(fromCache, collectedAt, sourcesQueried, failures, false, false);
    }

    public SearchMeta(boolean fromCache, Instant collectedAt, List<String> sourcesQueried,
                      List<SourceFailure> failures, boolean truncated) {
        this(fromCache, collectedAt, sourcesQueried, failures, truncated, false);
    }

    public SearchMeta withTruncated(boolean value) {
        return new SearchMeta(fromCache, collectedAt, sourcesQueried, failures, value, refreshing);
    }

    public SearchMeta withRefreshing(boolean value) {
        return new SearchMeta(fromCache, collectedAt, sourcesQueried, failures, truncated, value);
    }

    public static SearchMeta cached(Instant collectedAt) {
        return new SearchMeta(true, collectedAt, List.of(), List.of());
    }
}
