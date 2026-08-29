package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.entity.SalaryPeriod;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SalaryParserTest {

    private final SalaryParser parser = new SalaryParser();

    @Test
    @DisplayName("faixa em reais com separador de milhar")
    void parsesBrazilianRange() {
        var salary = parser.parse("R$ 4.500,00 a R$ 7.000,00 por mês");

        assertThat(salary.min()).isEqualByComparingTo(new BigDecimal("4500.00"));
        assertThat(salary.max()).isEqualByComparingTo(new BigDecimal("7000.00"));
        assertThat(salary.currency()).isEqualTo("BRL");
        assertThat(salary.period()).isEqualTo(SalaryPeriod.MONTH);
    }

    @Test
    @DisplayName("faixa em dólar no formato americano")
    void parsesUsRange() {
        var salary = parser.parse("$90,000 - $120,000 per year");

        assertThat(salary.min()).isEqualByComparingTo(new BigDecimal("90000"));
        assertThat(salary.max()).isEqualByComparingTo(new BigDecimal("120000"));
        assertThat(salary.currency()).isEqualTo("USD");
        assertThat(salary.period()).isEqualTo(SalaryPeriod.YEAR);
    }

    @Test
    @DisplayName("sufixo k é expandido para milhares")
    void expandsThousandSuffix() {
        var salary = parser.parse("USD 80k - 110k / year");

        assertThat(salary.min()).isEqualByComparingTo(new BigDecimal("80000"));
        assertThat(salary.max()).isEqualByComparingTo(new BigDecimal("110000"));
    }

    @Test
    @DisplayName("valor único vira piso, sem inventar teto")
    void singleValueBecomesMinimum() {
        var salary = parser.parse("A partir de R$ 3.200 mensais");

        assertThat(salary.min()).isEqualByComparingTo(new BigDecimal("3200"));
        assertThat(salary.max()).isNull();
    }

    @Test
    @DisplayName("faixa invertida é corrigida")
    void fixesInvertedRange() {
        var salary = parser.parse("R$ 9.000 a R$ 6.000");

        assertThat(salary.min()).isEqualByComparingTo(new BigDecimal("6000"));
        assertThat(salary.max()).isEqualByComparingTo(new BigDecimal("9000"));
    }

    @Test
    @DisplayName("valor por hora é reconhecido")
    void parsesHourly() {
        var salary = parser.parse("USD 4500 per hour");

        assertThat(salary.period()).isEqualTo(SalaryPeriod.HOUR);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"A combinar", "Salário competitivo", "5 anos de experiência"})
    @DisplayName("texto sem valor plausível não vira número")
    void doesNotInventNumbers(String raw) {
        assertThat(parser.parse(raw).isEmpty()).isTrue();
    }
}
