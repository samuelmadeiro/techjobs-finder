package com.techjobs.finder.dto.job;

import com.techjobs.finder.entity.SalaryPeriod;
import java.math.BigDecimal;

/**
 * Faixa salarial. {@code raw} preserva o texto original do anúncio, porque nem toda
 * fonte publica valores estruturados e é melhor mostrar o texto do que omitir o salário.
 */
public record SalaryResponse(
        BigDecimal min,
        BigDecimal max,
        String currency,
        SalaryPeriod period,
        String raw) {

    public boolean isEmpty() {
        return min == null && max == null && (raw == null || raw.isBlank());
    }
}
