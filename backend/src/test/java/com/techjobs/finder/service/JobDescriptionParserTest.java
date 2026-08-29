package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobDescriptionParserTest {

    private final JobDescriptionParser parser = new JobDescriptionParser();

    @Test
    @DisplayName("separa requisitos de diferenciais pelos títulos do anúncio")
    void splitsRequirementsAndNiceToHave() {
        String html = """
                <p>Procuramos uma pessoa desenvolvedora backend.</p>
                <h3>Requisitos</h3>
                <ul>
                  <li>Experiência com Java</li>
                  <li>Spring Boot</li>
                  <li>Git</li>
                </ul>
                <h3>Diferenciais</h3>
                <ul>
                  <li>Docker</li>
                  <li>AWS</li>
                </ul>
                <h3>Benefícios</h3>
                <ul><li>Vale refeição</li></ul>
                """;

        var parsed = parser.parse(html);

        assertThat(parsed.requirements()).containsExactly("Experiência com Java", "Spring Boot", "Git");
        assertThat(parsed.niceToHave()).containsExactly("Docker", "AWS");
        // "Benefícios" encerra a seção: o vale refeição não pode virar diferencial.
        assertThat(parsed.niceToHave()).doesNotContain("Vale refeição");
    }

    @Test
    @DisplayName("preserva as quebras de linha do anúncio no texto completo")
    void keepsLineBreaks() {
        var parsed = parser.parse("<p>Primeira linha.</p><p>Segunda linha.</p>");

        assertThat(parsed.plainText()).contains("Primeira linha.").contains("Segunda linha.");
        assertThat(parsed.plainText().lines().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("anúncio sem lista usa as linhas com marcador")
    void fallsBackToBulletLines() {
        String html = """
                <p>Sobre a vaga.<br>
                Requisitos:<br>
                - Java 17<br>
                - PostgreSQL<br>
                Benefícios:<br>
                - Plano de saúde</p>
                """;

        var parsed = parser.parse(html);

        assertThat(parsed.requirements()).containsExactly("Java 17", "PostgreSQL");
        assertThat(parsed.requirements()).doesNotContain("Plano de saúde");
    }

    @Test
    @DisplayName("parágrafo que só menciona a palavra requisito não abre seção")
    void ignoresRequirementWordInsideProse() {
        String html = "<p>Um dos requisitos mais importantes para nós é que a pessoa goste de "
                + "trabalhar em time e queira aprender continuamente com os colegas do time.</p>"
                + "<ul><li>Item solto</li></ul>";

        var parsed = parser.parse(html);

        assertThat(parsed.requirements()).isEmpty();
    }

    @Test
    @DisplayName("resumo curto corta em fim de frase")
    void shortensAtSentenceBoundary() {
        String longText = "<p>" + "Frase completa de exemplo. ".repeat(40) + "</p>";

        var parsed = parser.parse(longText);

        assertThat(parsed.shortText()).hasSizeLessThanOrEqualTo(400);
        assertThat(parsed.shortText()).endsWith(".");
    }

    @Test
    @DisplayName("HTML vazio ou nulo devolve resultado vazio, não exceção")
    void handlesEmptyInput() {
        assertThat(parser.parse(null).plainText()).isNull();
        assertThat(parser.parse("   ").requirements()).isEmpty();
    }
}
