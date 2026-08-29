package com.techjobs.finder.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlugsTest {

    @ParameterizedTest
    @CsvSource({
            "Java, java",
            "C#, csharp",
            "C++, cpp",
            "Node.js, nodejs",
            "Spring Boot, spring-boot",
            "'  TypeScript  ', typescript",
            ".NET, dotnet",
            "PostgreSQL, postgresql"
    })
    @DisplayName("converte nome de exibição em slug canônico")
    void toSlug(String input, String expected) {
        assertThat(Slugs.toSlug(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("aceita lista separada por vírgula e remove duplicatas")
    void normalizeAll() {
        assertThat(Slugs.normalizeAll(List.of("java,go", "Java", " Spring Boot ")))
                .containsExactly("java", "go", "spring-boot");
    }

    @Test
    @DisplayName("valores vazios são descartados")
    void normalizeAllIgnoresBlank() {
        assertThat(Slugs.normalizeAll(List.of("", "   ", ","))).isEmpty();
        assertThat(Slugs.normalizeAll(null)).isEmpty();
    }

    @Test
    @DisplayName("volta do slug para o nome de exibição")
    void toDisplay() {
        assertThat(Slugs.toDisplay("spring-boot")).isEqualTo("Spring Boot");
        assertThat(Slugs.toDisplay("csharp")).isEqualTo("C#");
        assertThat(Slugs.toDisplay("rust")).isEqualTo("Rust");
    }
}
