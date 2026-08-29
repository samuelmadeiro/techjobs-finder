package com.techjobs.finder.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** Limites e pesos do currículo, ajustáveis por configuração sem recompilar. */
@Component
@ConfigurationProperties(prefix = "techjobs.resume")
public class ResumeProperties {

    /** Teto do arquivo aceito no upload. */
    private DataSize maxFileSize = DataSize.ofMegabytes(5);

    /** Extensões aceitas, sem ponto. */
    private List<String> allowedExtensions = List.of("pdf", "docx");

    /** Tipos MIME aceitos, verificados pelo conteúdo e não pelo cabeçalho do cliente. */
    private List<String> allowedContentTypes = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Máximo de caracteres de texto guardados por currículo. */
    private int maxTextLength = 200_000;

    /**
     * Por quanto tempo um currículo é guardado.
     *
     * <p>Currículo é dado pessoal: nome, localização, histórico e o arquivo original. A
     * vaga já era purgada depois de 60 dias e o currículo ficava para sempre, o que é o
     * contrário da ordem de importância. Passado esse prazo sem novo envio, o registro e o
     * binário são apagados; o usuário continua podendo enviar outro.
     */
    private Duration retention = Duration.ofDays(180);

    private final Weights weights = new Weights();

    /**
     * Pesos do algoritmo de compatibilidade. Somam 100 quando todos os critérios se aplicam;
     * critério não avaliável tem o peso redistribuído entre os demais.
     */
    public static class Weights {
        private int skills = 50;
        private int experience = 20;
        private int workModel = 10;
        private int location = 10;
        private int relatedTechnologies = 10;

        public int getSkills() {
            return skills;
        }

        public void setSkills(int skills) {
            this.skills = skills;
        }

        public int getExperience() {
            return experience;
        }

        public void setExperience(int experience) {
            this.experience = experience;
        }

        public int getWorkModel() {
            return workModel;
        }

        public void setWorkModel(int workModel) {
            this.workModel = workModel;
        }

        public int getLocation() {
            return location;
        }

        public void setLocation(int location) {
            this.location = location;
        }

        public int getRelatedTechnologies() {
            return relatedTechnologies;
        }

        public void setRelatedTechnologies(int relatedTechnologies) {
            this.relatedTechnologies = relatedTechnologies;
        }
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public Weights getWeights() {
        return weights;
    }
}
