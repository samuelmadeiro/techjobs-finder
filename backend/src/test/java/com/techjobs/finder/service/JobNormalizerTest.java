package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.scraper.RawJob;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobNormalizerTest {

    private final JobNormalizer normalizer =
            new JobNormalizer(new TechnologyCatalog(), new ExperienceLevelDetector(),
            new JobDescriptionParser(), new SalaryParser(), new CountryCatalog());

    private RawJob raw(String title, String description, String location) {
        return new RawJob()
                .setTitle(title)
                .setCompany("Empresa XYZ")
                .setLocation(location)
                .setUrl("https://exemplo.com/vaga/1")
                .setDescriptionHtml(description);
    }

    @Test
    @DisplayName("detecta estágio a partir do título")
    void detectsInternship() {
        var result = normalizer.normalize(raw("Estágio em Desenvolvimento Java", null, "João Pessoa - PB"), "teste");
        assertThat(result.experienceLevel()).isEqualTo(ExperienceLevel.INTERNSHIP);
    }

    @Test
    @DisplayName("título tem precedência sobre a descrição na definição do nível")
    void titleWinsOverDescription() {
        var result = normalizer.normalize(
                raw("Desenvolvedor Java Júnior", "Buscamos profissional sênior para o time", null), "teste");
        assertThat(result.experienceLevel()).isEqualTo(ExperienceLevel.JUNIOR);
    }

    @Test
    @DisplayName("híbrido vence remoto quando os dois aparecem no texto")
    void hybridBeatsRemote() {
        var result = normalizer.normalize(
                raw("Dev Backend", "Modelo híbrido, com dias remotos", "Recife"), "teste");
        assertThat(result.workModel()).isEqualTo(WorkModel.HYBRID);
    }

    @Test
    @DisplayName("dica explícita da fonte define a modalidade")
    void workModelHintWins() {
        RawJob job = raw("Dev Backend", "Atuação presencial no escritório", "Recife").setWorkModelHint("remote");
        assertThat(normalizer.normalize(job, "teste").workModel()).isEqualTo(WorkModel.REMOTE);
    }

    @Test
    @DisplayName("extrai linguagens e frameworks do texto e das tags")
    void detectsTechnologies() {
        RawJob job = raw("Desenvolvedor Java Júnior",
                "Experiência com Spring Boot e PostgreSQL. Docker é diferencial.", "Remoto")
                .addTags(List.of("kubernetes"));
        assertThat(normalizer.normalize(job, "teste").technologySlugs())
                .contains("java", "spring-boot", "postgresql", "docker", "kubernetes");
    }

    @Test
    @DisplayName("não confunde 'go' dentro de outra palavra com a linguagem Go")
    void avoidsFalsePositiveOnShortAlias() {
        var result = normalizer.normalize(raw("Analista", "Estamos going forward com o projeto", null), "teste");
        assertThat(result.technologySlugs()).doesNotContain("go");
    }

    @Test
    @DisplayName("mesma vaga em fontes diferentes gera o mesmo fingerprint")
    void fingerprintIsStableAcrossSources() {
        var a = normalizer.normalize(raw("Desenvolvedor Java", null, "Remoto"), "fonte-a");
        var b = normalizer.normalize(
                new RawJob().setTitle("desenvolvedor java")
                        .setCompany("Empresa XYZ Ltda")
                        .setLocation("remoto")
                        .setUrl("https://outro-site.com/j/9"),
                "fonte-b");
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    @DisplayName("sem empresa, o fingerprint isola por fonte e URL")
    void fingerprintFallsBackToUrl() {
        RawJob first = new RawJob().setTitle("Dev").setUrl("https://a.com/1");
        RawJob second = new RawJob().setTitle("Dev").setUrl("https://a.com/2");
        assertThat(normalizer.normalize(first, "x").fingerprint())
                .isNotEqualTo(normalizer.normalize(second, "x").fingerprint());
    }

    @Test
    @DisplayName("salário é extraído da descrição quando a fonte não informa o campo")
    void detectsSalaryInDescription() {
        var result = normalizer.normalize(
                raw("Dev Java", "Faixa salarial de R$ 5.000 a R$ 7.000 por mês", null), "teste");
        assertThat(result.salary()).contains("5.000");
    }

    @Test
    @DisplayName("valor de investimento não é confundido com salário")
    void ignoresFundingAmounts() {
        var result = normalizer.normalize(raw("Dev Java",
                "Backed by $100M from top investors, we scaled to $200M in ARR.", null), "teste");
        assertThat(result.salary()).isNull();
    }

    @Test
    @DisplayName("valor só é aceito perto de palavra de remuneração")
    void requiresSalaryContext() {
        var semContexto = normalizer.normalize(
                raw("Dev", "Nosso produto processa $250,000 em transações por dia.", null), "teste");
        var comContexto = normalizer.normalize(
                raw("Dev", "Compensation: $120,000 - $150,000 per year.", null), "teste");

        assertThat(semContexto.salary()).isNull();
        assertThat(comContexto.salary()).contains("120,000");
    }

    @Test
    @DisplayName("salário declarado pela fonte tem precedência sobre o texto")
    void declaredSalaryWins() {
        RawJob job = raw("Dev", "Salário de R$ 9.000", null).setSalaryRaw("USD 80.000 / ano");
        assertThat(normalizer.normalize(job, "teste").salary()).isEqualTo("USD 80.000 / ano");
    }

    @Test
    @DisplayName("descrição em HTML vira resumo em texto puro")
    void summaryIsPlainText() {
        var result = normalizer.normalize(raw("Dev", "<p>Vaga <b>legal</b></p>", null), "teste");
        assertThat(result.summary()).isEqualTo("Vaga legal");
    }
}
