package com.techjobs.finder.dto;

import com.techjobs.finder.dto.job.SearchMeta;
import java.util.List;

/** Envelope de paginação usado por todos os endpoints de listagem. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last,
        SearchMeta meta) {

    public static <T> PageResponse<T> of(List<T> all, int page, int size, SearchMeta meta) {
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(all.subList(from, to), page, size, total, totalPages,
                to >= total, meta);
    }
}
