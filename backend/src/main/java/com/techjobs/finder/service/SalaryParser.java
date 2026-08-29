package com.techjobs.finder.service;

import com.techjobs.finder.entity.SalaryPeriod;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Converte o texto de salário em valores comparáveis.
 *
 * <p>O texto original continua guardado: nem toda fonte publica faixa estruturada, e é
 * melhor exibir "a combinar" do que inventar um número. Quando não dá para interpretar
 * com segurança, devolve {@link ParsedSalary#empty()} em vez de arriscar um palpite.
 */
@Component
public class SalaryParser {

    /** Símbolo/código encontrado -> código ISO usado na API. */
    private static final Map<String, String> CURRENCIES = Map.of(
            "r$", "BRL",
            "brl", "BRL",
            "us$", "USD",
            "usd", "USD",
            "$", "USD",
            "€", "EUR",
            "eur", "EUR",
            "£", "GBP",
            "gbp", "GBP");

    private static final Pattern CURRENCY =
            Pattern.compile("(?i)(r\\$|us\\$|usd|brl|eur|gbp|€|£|\\$)");

    /** Números com separador de milhar, decimal ou sufixo k/mil. */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)\\s*(k|mil)?");

    private static final Pattern PERIOD = Pattern.compile(
            "(?i)(?:/|por|per|ao|a\\s+cada)?\\s*(hora|hour|hr|mes|m[eê]s|month|ano|year|yr|annum)");

    /** Abaixo disso o número não é salário (é "5 anos", "10 pessoas", um percentual). */
    private static final BigDecimal MIN_PLAUSIBLE = new BigDecimal("100");

    public record ParsedSalary(BigDecimal min, BigDecimal max, String currency, SalaryPeriod period) {

        public static ParsedSalary empty() {
            return new ParsedSalary(null, null, null, null);
        }

        public boolean isEmpty() {
            return min == null && max == null;
        }
    }

    public ParsedSalary parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedSalary.empty();
        }
        String text = raw.trim();

        BigDecimal first = null;
        BigDecimal second = null;
        Matcher amounts = AMOUNT.matcher(text);
        while (amounts.find() && second == null) {
            BigDecimal value = toAmount(amounts.group(1), amounts.group(2));
            if (value == null || value.compareTo(MIN_PLAUSIBLE) < 0) {
                continue;
            }
            if (first == null) {
                first = value;
            } else {
                second = value;
            }
        }
        if (first == null) {
            return ParsedSalary.empty();
        }

        BigDecimal min = first;
        BigDecimal max = second;
        // "8.000 a 5.000" não existe: se vier invertido, o menor é o piso.
        if (max != null && max.compareTo(min) < 0) {
            BigDecimal swap = min;
            min = max;
            max = swap;
        }

        return new ParsedSalary(min, max, detectCurrency(text), detectPeriod(text));
    }

    /**
     * Interpreta o separador pelo formato, não pela localidade: {@code 1.234,56} é
     * brasileiro e {@code 1,234.56} é americano — o último separador manda.
     */
    private BigDecimal toAmount(String digits, String suffix) {
        String cleaned = digits.trim();
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                cleaned = cleaned.replace(".", "").replace(',', '.');
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (lastComma >= 0) {
            // Vírgula única: decimal se sobrar 1-2 dígitos, separador de milhar caso contrário.
            cleaned = cleaned.length() - lastComma <= 3
                    ? cleaned.replace(',', '.')
                    : cleaned.replace(",", "");
        } else if (lastDot >= 0 && cleaned.length() - lastDot > 3) {
            cleaned = cleaned.replace(".", "");
        }

        try {
            BigDecimal value = new BigDecimal(cleaned);
            if (suffix != null) {
                value = value.multiply(BigDecimal.valueOf(1000));
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String detectCurrency(String text) {
        Matcher matcher = CURRENCY.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return CURRENCIES.get(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    private SalaryPeriod detectPeriod(String text) {
        Matcher matcher = PERIOD.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).toLowerCase(Locale.ROOT);
        if (value.startsWith("hora") || value.startsWith("hour") || value.equals("hr")) {
            return SalaryPeriod.HOUR;
        }
        if (value.startsWith("me") || value.startsWith("mê") || value.startsWith("month")) {
            return SalaryPeriod.MONTH;
        }
        return SalaryPeriod.YEAR;
    }
}
