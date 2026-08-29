package com.techjobs.finder.scraper.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Avaliação das regras de robots.txt sem tocar a rede. */
class RobotsTxtServiceTest {

    private static final String ROBOTS = """
            User-agent: *
            Disallow: /admin
            Disallow: /private/
            Allow: /private/publico

            User-agent: BadBot
            Disallow: /
            """;

    private RobotsTxtService.CachedRules rules() {
        return RobotsTxtService.CachedRules.parse(ROBOTS, "TechJobsFinder/0.1");
    }

    @Test
    @DisplayName("caminho não listado é permitido")
    void allowsUnlistedPath() {
        assertThat(rules().allows("/api/remote-jobs")).isTrue();
    }

    @Test
    @DisplayName("caminho bloqueado no grupo curinga é negado")
    void deniesDisallowedPath() {
        assertThat(rules().allows("/admin/config")).isFalse();
        assertThat(rules().allows("/private/dados")).isFalse();
    }

    @Test
    @DisplayName("Allow mais específico vence o Disallow mais curto")
    void longestMatchWins() {
        assertThat(rules().allows("/private/publico/vaga")).isTrue();
    }

    @Test
    @DisplayName("grupo específico do nosso user-agent tem precedência sobre o curinga")
    void specificGroupWins() {
        String robots = """
                User-agent: *
                Disallow: /

                User-agent: TechJobsFinder
                Allow: /api
                Disallow: /admin
                """;
        var parsed = RobotsTxtService.CachedRules.parse(robots, "TechJobsFinder/0.1");

        assertThat(parsed.allows("/api/jobs")).isTrue();
        assertThat(parsed.allows("/admin")).isFalse();
    }

    @Test
    @DisplayName("curinga e âncora de fim são respeitados")
    void supportsWildcardAndAnchor() {
        String robots = """
                User-agent: *
                Disallow: /*.json$
                """;
        var parsed = RobotsTxtService.CachedRules.parse(robots, "TechJobsFinder/0.1");

        assertThat(parsed.allows("/dados/arquivo.json")).isFalse();
        assertThat(parsed.allows("/dados/arquivo.json?x=1")).isTrue();
        assertThat(parsed.allows("/dados/pagina.html")).isTrue();
    }

    @Test
    @DisplayName("robots.txt vazio libera tudo")
    void emptyRobotsAllowsEverything() {
        assertThat(RobotsTxtService.CachedRules.parse("", "TechJobsFinder/0.1").allows("/qualquer")).isTrue();
    }
}
