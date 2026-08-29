package com.techjobs.finder.dto.job;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.util.Slugs;
import com.techjobs.finder.util.Text;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Filtros normalizados de busca. Instâncias são criadas por {@code JobSearchRequest#toFilter()},
 * portanto todo valor aqui já passou por validação e normalização.
 *
 * @param languages   slugs de linguagens (ex.: {@code java})
 * @param technologies slugs de tecnologias (ex.: {@code spring-boot})
 * @param level       nível desejado; {@code null} significa "todos os níveis"
 * @param workModel   modalidade desejada; {@code null} significa "todas"
 * @param country     código ISO-3166 alpha-2 do país desejado; {@code null} = qualquer país
 * @param location    texto livre de localização
 * @param keyword     texto livre pesquisado em título/descrição
 * @param sources     códigos de fonte a considerar; vazio = todas
 */
public record JobSearchFilter(
        List<String> languages,
        List<String> technologies,
        ExperienceLevel level,
        WorkModel workModel,
        String country,
        String location,
        String keyword,
        List<String> sources) {

    public JobSearchFilter {
        languages = languages == null ? List.of() : List.copyOf(languages);
        technologies = technologies == null ? List.of() : List.copyOf(technologies);
        sources = sources == null ? List.of() : List.copyOf(sources);
        country = Text.blankToNull(country);
        location = Text.blankToNull(location);
        keyword = Text.blankToNull(keyword);
    }

    public static JobSearchFilter empty() {
        return new JobSearchFilter(List.of(), List.of(), null, null, null, null, null, List.of());
    }

    /** Todos os slugs de tecnologia pedidos, linguagens incluídas. */
    public List<String> allTechnologySlugs() {
        return java.util.stream.Stream.concat(languages.stream(), technologies.stream())
                .distinct()
                .toList();
    }

    public boolean hasTechnologyCriteria() {
        return !languages.isEmpty() || !technologies.isEmpty();
    }

    /**
     * Termo de busca enviado às fontes externas. Combina palavra-chave e tecnologias porque
     * a maioria das APIs aceita apenas um campo de texto livre.
     */
    public String toQueryText() {
        StringBuilder builder = new StringBuilder();
        if (keyword != null) {
            builder.append(keyword);
        }
        for (String slug : allTechnologySlugs()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Slugs.toDisplay(slug));
        }
        return builder.toString().trim();
    }

    /** Identidade estável do filtro, usada como chave do cache de busca. */
    public String fingerprint() {
        String canonical = String.join("|",
                String.join(",", languages.stream().sorted().toList()),
                String.join(",", technologies.stream().sorted().toList()),
                Objects.toString(level, ""),
                Objects.toString(workModel, ""),
                // O país entra no canônico como qualquer outro critério: é o que faz
                // BR+Java+Júnior e US+Java+Júnior serem buscas diferentes, com cache
                // separado e coleta separada. Nenhum segundo fingerprint foi criado.
                Objects.toString(country, ""),
                Objects.toString(Text.normalize(location), ""),
                Objects.toString(Text.normalize(keyword), ""),
                String.join(",", sources.stream().sorted().toList()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
