package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.ResumeItemKind;
import com.techjobs.finder.entity.WorkModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResumeParserServiceTest {

    private final ResumeParserService parser = new ResumeParserService(new TechnologyCatalog());

    private static final String RESUME = """
            Samuel Borba Madeiro
            Desenvolvedor Backend Java
            samuel@exemplo.com | (83) 99999-0000
            João Pessoa - PB | Aberto a vagas remoto

            Resumo profissional
            Desenvolvedor com 3 anos de experiência construindo APIs REST.

            Experiência profissional
            Desenvolvedor Java Pleno na Empresa XYZ, atuando com Java e Spring Boot.
            Estágio em desenvolvimento na Empresa ABC, com foco em PostgreSQL.

            Formação acadêmica
            Bacharelado em Ciência da Computação - UFPB

            Certificações
            AWS Cloud Practitioner

            Projetos
            Sistema backend com Spring Boot e Docker publicado no GitHub.
            """;

    @Test
    @DisplayName("extrai nome e headline das primeiras linhas")
    void extractsNameAndHeadline() {
        var parsed = parser.parse(RESUME);

        assertThat(parsed.candidateName()).isEqualTo("Samuel Borba Madeiro");
        assertThat(parsed.headline()).isEqualTo("Desenvolvedor Backend Java");
    }

    @Test
    @DisplayName("reconhece as tecnologias do catálogo com contagem de ocorrências")
    void detectsSkills() {
        var parsed = parser.parse(RESUME);

        assertThat(parsed.skillCounts()).containsKeys("java", "spring-boot", "postgresql", "docker", "aws");
        assertThat(parsed.skillCounts().get("java")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("nível vem do rótulo citado na experiência, não do texto inteiro")
    void detectsLevelFromExperienceSection() {
        var parsed = parser.parse(RESUME);

        // "Estágio" aparece no currículo, mas "Pleno" é o cargo mais alto declarado.
        assertThat(parsed.experienceLevel()).isEqualTo(ExperienceLevel.MID);
        assertThat(parsed.experienceYears()).isEqualTo(3);
    }

    @Test
    @DisplayName("classifica os itens na seção a que pertencem")
    void assignsItemsToSections() {
        var parsed = parser.parse(RESUME);

        assertThat(parsed.itemsOf(ResumeItemKind.EDUCATION))
                .anySatisfy(item -> assertThat(item).contains("Ciência da Computação"));
        assertThat(parsed.itemsOf(ResumeItemKind.CERTIFICATION))
                .anySatisfy(item -> assertThat(item).contains("AWS"));
        assertThat(parsed.itemsOf(ResumeItemKind.PROJECT))
                .anySatisfy(item -> assertThat(item).contains("Spring Boot"));
        // A linha da formação não pode vazar para a experiência.
        assertThat(parsed.itemsOf(ResumeItemKind.EXPERIENCE))
                .noneSatisfy(item -> assertThat(item).contains("UFPB"));
    }

    @Test
    @DisplayName("preferência por trabalho remoto é reconhecida")
    void detectsRemotePreference() {
        assertThat(parser.parse(RESUME).preferredWorkModel()).isEqualTo(WorkModel.REMOTE);
    }

    @Test
    @DisplayName("cidade e estado do cabeçalho viram a localização do candidato")
    void extractsLocationFromHeader() {
        assertThat(parser.parse(RESUME).location()).isEqualTo("João Pessoa - PB");
    }

    @Test
    @DisplayName("cidade é encontrada mesmo na linha que também tem e-mail")
    void extractsLocationFromContactLine() {
        var parsed = parser.parse("Ana Souza\nDesenvolvedora\nana@exemplo.com | Recife/PE\n");

        assertThat(parsed.location()).isEqualTo("Recife - PE");
    }

    @Test
    @DisplayName("currículo sem cidade no cabeçalho não inventa localização")
    void locationIsNullWhenAbsent() {
        var parsed = parser.parse("Ana Souza\nDesenvolvedora Python\nana@exemplo.com");

        assertThat(parsed.location()).isNull();
    }

    @Test
    @DisplayName("sem sinal de senioridade o nível vem dos anos declarados")
    void fallsBackToYears() {
        var parsed = parser.parse("Ana Souza\nDesenvolvedora\n\nExperiência\n"
                + "Atuo há 8 anos de experiência com Python e Django.");

        assertThat(parsed.experienceLevel()).isEqualTo(ExperienceLevel.SENIOR);
    }

    @Test
    @DisplayName("currículo vazio devolve perfil vazio, não exceção")
    void handlesEmptyResume() {
        var parsed = parser.parse("");

        assertThat(parsed.experienceLevel()).isEqualTo(ExperienceLevel.UNKNOWN);
        assertThat(parsed.skillCounts()).isEmpty();
        assertThat(parsed.candidateName()).isNull();
    }
}
