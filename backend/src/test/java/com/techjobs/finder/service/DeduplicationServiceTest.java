package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.entity.Company;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.service.JobNormalizer.Normalized;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeduplicationServiceTest {

    private final JobNormalizer normalizer =
            new JobNormalizer(new TechnologyCatalog(), new ExperienceLevelDetector(),
            new JobDescriptionParser(), new SalaryParser(), new CountryCatalog());
    private final DeduplicationService service = new DeduplicationService();

    private Normalized normalized(String title, String company, String location, String url, String source) {
        return normalizer.normalize(new RawJob()
                .setTitle(title)
                .setCompany(company)
                .setLocation(location)
                .setUrl(url)
                .setSourceCode(source), source);
    }

    @Test
    @DisplayName("mesma vaga vinda de duas fontes é colapsada em um registro")
    void collapsesAcrossSources() {
        List<Normalized> batch = List.of(
                normalized("Desenvolvedor Java Júnior", "Empresa XYZ", "Remoto", "https://a.com/1", "a"),
                normalized("desenvolvedor java junior", "Empresa XYZ Ltda", "remoto", "https://b.com/9", "b"));

        assertThat(service.collapse(batch)).hasSize(1);
    }

    @Test
    @DisplayName("títulos parecidos da mesma empresa contam como a mesma vaga")
    void collapsesSimilarTitles() {
        List<Normalized> batch = List.of(
                normalized("Desenvolvedor Backend Java Pleno", "ACME", "São Paulo", "https://a.com/1", "a"),
                normalized("Desenvolvedor Backend Java Pleno ", "ACME", "São Paulo", "https://b.com/2", "b"));

        assertThat(service.collapse(batch)).hasSize(1);
    }

    @Test
    @DisplayName("vagas diferentes da mesma empresa são mantidas")
    void keepsDistinctJobs() {
        List<Normalized> batch = List.of(
                normalized("Desenvolvedor Java Júnior", "ACME", "Recife", "https://a.com/1", "a"),
                normalized("Analista de Dados Python Sênior", "ACME", "Recife", "https://a.com/2", "a"));

        assertThat(service.collapse(batch)).hasSize(2);
    }

    @Test
    @DisplayName("a mesma URL com query string diferente não vira duas vagas")
    void collapsesByCanonicalUrl() {
        List<Normalized> batch = List.of(
                normalized("Dev", null, null, "https://a.com/vaga/1?utm_source=x", "a"),
                normalized("Dev", null, null, "https://a.com/vaga/1", "a"));

        assertThat(service.collapse(batch)).hasSize(1);
    }

    @Test
    @DisplayName("entre duplicatas mantém a que traz mais tecnologias detectadas")
    void keepsRicherRecord() {
        Normalized poor = normalized("Dev Java", "ACME", "Remoto", "https://a.com/1", "a");
        Normalized rich = normalizer.normalize(new RawJob()
                .setTitle("Dev Java")
                .setCompany("ACME")
                .setLocation("Remoto")
                .setUrl("https://b.com/1")
                .setDescriptionHtml("Spring Boot, PostgreSQL e Docker")
                .setSourceCode("b"), "b");

        List<Normalized> result = service.collapse(List.of(poor, rich));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).technologySlugs()).contains("spring-boot", "postgresql", "docker");
    }

    @Test
    @DisplayName("encontra duplicata já persistida da mesma empresa")
    void findsExistingDuplicate() {
        Job existing = new Job();
        existing.setTitle("Desenvolvedor Java Júnior");
        existing.setLocation("Remoto");
        existing.setCompany(new Company("ACME", "acme"));

        var candidate = normalized("Desenvolvedor Java Junior", "ACME", "Remoto", "https://b.com/1", "b");

        assertThat(service.findExistingDuplicate(candidate, List.of(existing))).isPresent();
    }

    @Test
    @DisplayName("vaga com título diferente não é tratada como duplicata")
    void doesNotMatchDifferentTitle() {
        Job existing = new Job();
        existing.setTitle("Analista de Suporte");
        existing.setLocation("Remoto");

        var candidate = normalized("Desenvolvedor Java Júnior", "ACME", "Remoto", "https://b.com/1", "b");

        assertThat(service.findExistingDuplicate(candidate, List.of(existing))).isEmpty();
    }
}
