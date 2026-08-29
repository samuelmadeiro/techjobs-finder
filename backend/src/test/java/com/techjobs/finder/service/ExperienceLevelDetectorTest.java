package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.entity.ExperienceLevel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExperienceLevelDetectorTest {

    private final ExperienceLevelDetector detector = new ExperienceLevelDetector();

    private ExperienceLevel fromDescription(String description) {
        return detector.detect("Pessoa Desenvolvedora Backend", null, description, List.of());
    }

    @ParameterizedTest
    @CsvSource({
            "Estágio em Desenvolvimento Java, INTERNSHIP",
            "Trainee de Engenharia de Software, TRAINEE",
            "Desenvolvedor Java Júnior, JUNIOR",
            "Desenvolvedor Backend Pleno, MID",
            "Senior Software Engineer, SENIOR",
            "Tech Lead de Plataforma, SENIOR",
            "Analista de Sistemas, UNKNOWN"
    })
    @DisplayName("título continua sendo o sinal mais forte")
    void detectsFromTitle(String title, ExperienceLevel expected) {
        assertThat(detector.detect(title, null, null, List.of())).isEqualTo(expected);
    }

    @Test
    @DisplayName("campo de senioridade da fonte é aproveitado")
    void usesSourceHint() {
        assertThat(detector.detect("Software Engineer", "Senior", null, List.of()))
                .isEqualTo(ExperienceLevel.SENIOR);
        assertThat(detector.detect("Software Engineer", "Entry-level", null, List.of()))
                .isEqualTo(ExperienceLevel.JUNIOR);
    }

    @Test
    @DisplayName("tags da fonte também contam como dica de nível")
    void usesTags() {
        assertThat(detector.detect("Software Engineer", null, null, List.of("remote", "internship")))
                .isEqualTo(ExperienceLevel.INTERNSHIP);
    }

    @ParameterizedTest
    @CsvSource({
            "'Buscamos alguém com no mínimo 8 anos de experiência em backend.', SENIOR",
            "'Requisitos: 4 anos de experiência com Java.', MID",
            "'Experiência de 3 a 5 anos com desenvolvimento web.', MID",
            "'Pelo menos 2 anos de experiência com Python.', JUNIOR",
            "'At least 7 years of experience building distributed systems.', SENIOR",
            "'1+ years of experience with React.', JUNIOR"
    })
    @DisplayName("deduz o nível pelos anos de experiência exigidos")
    void detectsFromYears(String description, ExperienceLevel expected) {
        assertThat(fromDescription(description)).isEqualTo(expected);
    }

    @Test
    @DisplayName("com vários números, vale a menor exigência")
    void usesMinimumYears() {
        String description = "Requisitos: 2 anos de experiência com Java; desejável 8 anos com cloud.";
        assertThat(detector.extractYearsOfExperience(description)).isEqualTo(2);
        assertThat(fromDescription(description)).isEqualTo(ExperienceLevel.JUNIOR);
    }

    @ParameterizedTest
    @CsvSource({
            "'Oferecemos bolsa auxílio e vale transporte. Necessário estar cursando graduação.', INTERNSHIP",
            "'Programa de trainee para recém-formados em TI.', TRAINEE",
            "'Vaga ideal para quem está no início de carreira, sem experiência prévia exigida.', JUNIOR",
            "'Você vai liderar o time de plataforma e definir a arquitetura dos serviços.', SENIOR"
    })
    @DisplayName("deduz o nível por expressões típicas, sem o rótulo aparecer")
    void detectsFromPhrases(String description, ExperienceLevel expected) {
        assertThat(fromDescription(description)).isEqualTo(expected);
    }

    @Test
    @DisplayName("título vence a descrição quando os dois divergem")
    void titleOutweighsDescription() {
        ExperienceLevel level = detector.detect(
                "Desenvolvedor Java Júnior",
                null,
                "Você trabalhará junto de pessoas sênior, com 10 anos de experiência no time.",
                List.of());
        assertThat(level).isEqualTo(ExperienceLevel.JUNIOR);
    }

    @Test
    @DisplayName("descrição decide quando o título é genérico")
    void descriptionDecidesForGenericTitle() {
        assertThat(fromDescription("Procuramos profissional com 6 anos de experiência em Java."))
                .isEqualTo(ExperienceLevel.SENIOR);
    }

    @Test
    @DisplayName("sinais somados vencem um sinal isolado mais forte")
    void combinedSignalsWin() {
        // Título genérico; descrição traz anos + expressão de liderança, ambos apontando sênior.
        ExperienceLevel level = detector.detect(
                "Engenheiro de Software",
                null,
                "Mínimo de 7 anos de experiência. Você vai mentorar pessoas do time e definir a arquitetura.",
                List.of());
        assertThat(level).isEqualTo(ExperienceLevel.SENIOR);
    }

    @Test
    @DisplayName("sem nenhum sinal, o nível fica desconhecido em vez de chutado")
    void noSignalMeansUnknown() {
        assertThat(detector.detect("Pessoa Desenvolvedora", null,
                "Venha trabalhar com a gente em um time incrível.", List.of()))
                .isEqualTo(ExperienceLevel.UNKNOWN);
        assertThat(detector.extractYearsOfExperience("Venha trabalhar com a gente.")).isNull();
    }

    @Test
    @DisplayName("número absurdo de anos é ignorado")
    void ignoresImplausibleYears() {
        assertThat(detector.extractYearsOfExperience("Empresa com 45 anos de mercado.")).isNull();
    }
}
