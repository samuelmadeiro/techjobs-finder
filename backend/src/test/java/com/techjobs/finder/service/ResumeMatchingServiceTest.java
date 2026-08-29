package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.config.ResumeProperties;
import com.techjobs.finder.dto.recommendation.CompatibilityResult.Recommendation;
import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.entity.TechnologyKind;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.service.ResumeMatchingService.ResumeProfile;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResumeMatchingServiceTest {

    private final ResumeMatchingService matching = new ResumeMatchingService(new ResumeProperties());

    private Job job(ExperienceLevel level, WorkModel model, String... technologySlugs) {
        Job job = new Job();
        job.setTitle("Vaga de teste");
        job.setExperienceLevel(level);
        job.setWorkModel(model);
        job.setTechnologies(Arrays.stream(technologySlugs)
                .map(slug -> new Technology(slug, slug, TechnologyKind.TOOL))
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return job;
    }

    private ResumeProfile profile(ExperienceLevel level, WorkModel preferred, String... skills) {
        Set<String> slugs = new LinkedHashSet<>(Arrays.asList(skills));
        Map<String, String> names = slugs.stream().collect(Collectors.toMap(s -> s, s -> s));
        return new ResumeProfile(slugs, names, level, null, preferred, null);
    }

    @Test
    @DisplayName("perfil que atende todos os critérios chega a 100")
    void perfectMatchScoresFull() {
        var result = matching.match(
                profile(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot", "postgresql"),
                job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot", "postgresql"));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.recommendation()).isEqualTo(Recommendation.HIGH);
        assertThat(result.matchedSkills()).containsExactly("java", "spring-boot", "postgresql");
        assertThat(result.missingSkills()).isEmpty();
    }

    @Test
    @DisplayName("tecnologia faltante aparece em missingSkills e derruba o score")
    void reportsMissingSkills() {
        var result = matching.match(
                profile(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot"),
                job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java", "spring-boot", "aws"));

        assertThat(result.missingSkills()).containsExactly("aws");
        assertThat(result.score()).isLessThan(100).isGreaterThan(50);
        assertThat(result.reasons())
                .anySatisfy(reason -> assertThat(reason.text()).contains("aws"));
    }

    @Test
    @DisplayName("tecnologia relacionada compensa parte da lacuna")
    void relatedTechnologyEarnsPartialCredit() {
        var withRelated = matching.match(
                profile(ExperienceLevel.MID, WorkModel.REMOTE, "java", "mysql"),
                job(ExperienceLevel.MID, WorkModel.REMOTE, "java", "postgresql"));
        var withoutRelated = matching.match(
                profile(ExperienceLevel.MID, WorkModel.REMOTE, "java", "kafka"),
                job(ExperienceLevel.MID, WorkModel.REMOTE, "java", "postgresql"));

        assertThat(withRelated.score()).isGreaterThan(withoutRelated.score());
    }

    @Test
    @DisplayName("um degrau acima do nível pedido não penaliza")
    void oneLevelAboveIsNotPenalized() {
        var result = matching.match(
                profile(ExperienceLevel.MID, WorkModel.REMOTE, "java"),
                job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java"));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.experienceMatch()).isTrue();
    }

    @Test
    @DisplayName("muito acima do nível pedido desconta pouco, mas desconta")
    void farAboveGetsSmallDiscount() {
        var overqualified = matching.match(
                profile(ExperienceLevel.SENIOR, WorkModel.REMOTE, "java"),
                job(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java"));
        var exact = matching.match(
                profile(ExperienceLevel.SENIOR, WorkModel.REMOTE, "java"),
                job(ExperienceLevel.SENIOR, WorkModel.REMOTE, "java"));

        // Sênior vendo vaga júnior à frente de vaga sênior seria uma recomendação ruim.
        assertThat(overqualified.score()).isLessThan(exact.score()).isGreaterThanOrEqualTo(90);
    }

    @Test
    @DisplayName("nível muito abaixo do pedido zera o critério de experiência")
    void juniorForSeniorRoleLosesLevelPoints() {
        var result = matching.match(
                profile(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java"),
                job(ExperienceLevel.SENIOR, WorkModel.REMOTE, "java"));

        assertThat(result.experienceMatch()).isFalse();
        assertThat(result.score()).isLessThan(100);
    }

    @Test
    @DisplayName("vaga sem critério avaliável não vira endosso nem rejeição")
    void neutralWhenNothingToCompare() {
        var result = matching.match(
                profile(ExperienceLevel.UNKNOWN, null),
                job(ExperienceLevel.UNKNOWN, WorkModel.UNKNOWN));

        assertThat(result.score()).isEqualTo(50);
    }

    @Test
    @DisplayName("cada critério avaliado gera uma linha de explicação")
    void alwaysExplainsTheScore() {
        var result = matching.match(
                profile(ExperienceLevel.JUNIOR, WorkModel.REMOTE, "java"),
                job(ExperienceLevel.JUNIOR, WorkModel.ONSITE, "java", "aws"));

        assertThat(result.reasons()).isNotEmpty();
        assertThat(result.reasons()).anyMatch(reason -> !reason.positive());
        assertThat(result.reasons()).anyMatch(reason -> reason.positive());
        assertThat(result.workModelMatch()).isFalse();
    }
}
