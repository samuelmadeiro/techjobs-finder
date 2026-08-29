package com.techjobs.finder.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    @DisplayName("normaliza removendo acentos e colapsando espaços")
    void normalize() {
        assertThat(Text.normalize("  Desenvolvedor JÚNIOR  Back-End ")).isEqualTo("desenvolvedor junior back-end");
    }

    @Test
    @DisplayName("mantém os caracteres que distinguem C++ e C#")
    void normalizeKeepsSymbols() {
        assertThat(Text.normalize("C++ e C#")).isEqualTo("c++ e c#");
    }

    @Test
    @DisplayName("remove sufixos societários do nome da empresa")
    void normalizeCompany() {
        assertThat(Text.normalizeCompany("ACME Tecnologia LTDA")).isEqualTo("acme tecnologia");
        assertThat(Text.normalizeCompany("Acme Inc.")).isEqualTo("acme");
    }

    @Test
    @DisplayName("descrição HTML vira texto puro, sem tags nem script")
    void stripHtml() {
        String html = "<p>Vaga <b>Java</b></p><script>alert('x')</script>";
        assertThat(Text.stripHtml(html)).isEqualTo("Vaga Java");
    }

    @Test
    @DisplayName("HTML escapado pela fonte também é limpo")
    void stripHtmlHandlesEscapedMarkup() {
        String doubleEscaped = "&lt;h1&gt;T&#237;tulo&lt;/h1&gt;&lt;p&gt;Vaga Java&lt;/p&gt;";
        assertThat(Text.stripHtml(doubleEscaped)).isEqualTo("Título Vaga Java");
    }

    @Test
    @DisplayName("texto sem marcação passa inalterado")
    void stripHtmlKeepsPlainText() {
        assertThat(Text.stripHtml("Vaga para dev com 3 < 5 anos")).isEqualTo("Vaga para dev com 3 < 5 anos");
    }

    @Test
    @DisplayName("similaridade alta para títulos equivalentes e baixa para diferentes")
    void tokenSimilarity() {
        assertThat(Text.tokenSimilarity("Desenvolvedor Java Júnior", "Desenvolvedor Java Junior"))
                .isEqualTo(1.0);
        assertThat(Text.tokenSimilarity("Desenvolvedor Java Júnior", "Analista de Dados Python"))
                .isLessThan(0.2);
    }

    @Test
    @DisplayName("título com token repetido não quebra a similaridade")
    void tokenSimilarityWithRepeatedTokens() {
        assertThat(Text.tokenSimilarity("dev dev java", "dev java")).isEqualTo(1.0);
    }
}
