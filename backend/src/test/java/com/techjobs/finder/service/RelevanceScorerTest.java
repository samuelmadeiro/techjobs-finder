package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.entity.TechnologyKind;
import com.techjobs.finder.entity.WorkModel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelevanceScorerTest {

    private final RelevanceScorer scorer = new RelevanceScorer();

    private Job job(ExperienceLevel level, WorkModel model, String... techSlugs) {
        Job job = new Job();
        job.setTitle("Desenvolvedor");
        job.setExperienceLevel(level);
        job.setWorkModel(model);
        // Data antiga: isola o teste do bônus de recência.
        job.setPublishedAt(Instant.now().minus(90, ChronoUnit.DAYS));
        Set<Technology> technologies = new LinkedHashSet<>();
        for (String slug : techSlugs) {
            technologies.add(new Technology(slug, slug, TechnologyKind.LANGUAGE));
        }
        job.setTechnologies(technologies);
        return job;
    }

    private JobSearchFilter filter() {
        return new JobSearchFilter(List.of("java"), List.of("spring-boot"),
                ExperienceLevel.JUNIOR, WorkModel.REMOTE, null, null, null, List.of());
    }

    @Test
    @DisplayName("aderência total ao filtro vale 100")
    void perfectMatch() {
        int score = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot"), filter());
        assertThat(score).isEqualTo(100);
    }

    @Test
    @DisplayName("tecnologia relacionada pontua menos que a exata")
    void relatedTechnologyScoresLower() {
        int exact = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot"), filter());
        int related = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring"), filter());

        assertThat(related).isLessThan(exact).isGreaterThan(70);
    }

    @Test
    @DisplayName("nível adjacente reduz a pontuação sem zerá-la")
    void adjacentLevelScoresLower() {
        int junior = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot"), filter());
        int pleno = scorer.score(job(ExperienceLevel.MID, WorkModel.REMOTE, "java", "spring-boot"), filter());

        assertThat(pleno).isLessThan(junior).isGreaterThan(50);
    }

    @Test
    @DisplayName("stack totalmente diferente tem relevância baixa")
    void unrelatedStackScoresLow() {
        int score = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "python", "django"), filter());
        assertThat(score).isLessThan(60);
    }

    @Test
    @DisplayName("ordem esperada: exato > relacionado > nível adjacente > stack diferente")
    void rankingOrder() {
        int exact = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot"), filter());
        int related = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring"), filter());
        int otherLevel = scorer.score(job(ExperienceLevel.MID, WorkModel.REMOTE, "java", "spring-boot"), filter());
        int other = scorer.score(job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "python", "django"), filter());

        assertThat(exact).isGreaterThanOrEqualTo(related);
        assertThat(related).isGreaterThanOrEqualTo(otherLevel);
        assertThat(otherLevel).isGreaterThan(other);
    }

    @Test
    @DisplayName("critério não pedido não penaliza a vaga")
    void unspecifiedCriteriaDoNotPenalize() {
        JobSearchFilter onlyLanguage = new JobSearchFilter(List.of("java"), List.of(),
                null, null, null, null, null, List.of());
        int score = scorer.score(job(ExperienceLevel.SENIOR, WorkModel.ONSITE, "java"), onlyLanguage);
        assertThat(score).isEqualTo(100);
    }

    @Test
    @DisplayName("vaga recém-publicada recebe bônus de recência")
    void recencyBonus() {
        Job recent = job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "python");
        recent.setPublishedAt(Instant.now());
        Job old = job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "python");

        assertThat(scorer.score(recent, filter())).isGreaterThan(scorer.score(old, filter()));
    }
}
