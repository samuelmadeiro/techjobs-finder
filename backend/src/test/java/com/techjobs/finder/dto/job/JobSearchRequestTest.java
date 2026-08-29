package com.techjobs.finder.dto.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.exception.InvalidFilterException;
import com.techjobs.finder.service.CountryCatalog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobSearchRequestTest {

    /** Catálogo real: é ele que decide quais códigos de país existem. */
    private static final CountryCatalog COUNTRIES = new CountryCatalog();

    @Test
    @DisplayName("normaliza linguagem, tecnologia, nível e modalidade")
    void buildsFilter() {
        JobSearchRequest request = new JobSearchRequest();
        request.setLanguage(List.of("Java"));
        request.setTechnology(List.of("Spring Boot"));
        request.setLevel("JUNIOR");
        request.setWorkModel("REMOTE");

        JobSearchFilter filter = request.toFilter(COUNTRIES);

        assertThat(filter.languages()).containsExactly("java");
        assertThat(filter.technologies()).containsExactly("spring-boot");
        assertThat(filter.level()).isEqualTo(ExperienceLevel.JUNIOR);
        assertThat(filter.workModel()).isEqualTo(WorkModel.REMOTE);
    }

    @Test
    @DisplayName("aceita os rótulos em português usados na interface")
    void acceptsPortugueseLabels() {
        JobSearchRequest request = new JobSearchRequest();
        request.setLevel("estágio");
        request.setWorkModel("híbrido");

        JobSearchFilter filter = request.toFilter(COUNTRIES);

        assertThat(filter.level()).isEqualTo(ExperienceLevel.INTERNSHIP);
        assertThat(filter.workModel()).isEqualTo(WorkModel.HYBRID);
    }

    @Test
    @DisplayName("'todos' e 'todas' equivalem a não filtrar")
    void allMeansNoFilter() {
        JobSearchRequest request = new JobSearchRequest();
        request.setLevel("todos");
        request.setWorkModel("todas");

        JobSearchFilter filter = request.toFilter(COUNTRIES);

        assertThat(filter.level()).isNull();
        assertThat(filter.workModel()).isNull();
    }

    @Test
    @DisplayName("valor fora do domínio vira erro de filtro")
    void rejectsUnknownLevel() {
        JobSearchRequest request = new JobSearchRequest();
        request.setLevel("chefe-supremo");

        assertThatThrownBy(() -> request.toFilter(COUNTRIES))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("level");
    }

    @Test
    @DisplayName("ordenação inválida é rejeitada")
    void rejectsUnknownSort() {
        JobSearchRequest request = new JobSearchRequest();
        request.setSort("aleatorio");

        assertThatThrownBy(request::sortMode).isInstanceOf(InvalidFilterException.class);
    }

    @Test
    @DisplayName("filtros equivalentes produzem o mesmo fingerprint de cache")
    void fingerprintIgnoresOrderAndCase() {
        JobSearchRequest first = new JobSearchRequest();
        first.setLanguage(List.of("java", "go"));
        JobSearchRequest second = new JobSearchRequest();
        second.setLanguage(List.of("GO", "Java"));

        assertThat(first.toFilter(COUNTRIES).fingerprint()).isEqualTo(second.toFilter(COUNTRIES).fingerprint());
    }

    @Test
    @DisplayName("filtros diferentes produzem fingerprints diferentes")
    void fingerprintDiffersByFilter() {
        JobSearchRequest first = new JobSearchRequest();
        first.setLanguage(List.of("java"));
        JobSearchRequest second = new JobSearchRequest();
        second.setLanguage(List.of("python"));

        assertThat(first.toFilter(COUNTRIES).fingerprint()).isNotEqualTo(second.toFilter(COUNTRIES).fingerprint());
    }
}
