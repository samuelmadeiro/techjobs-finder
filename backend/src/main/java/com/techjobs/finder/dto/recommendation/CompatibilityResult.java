package com.techjobs.finder.dto.recommendation;

import java.util.List;

/**
 * Compatibilidade entre um currículo e uma vaga.
 *
 * <p>O score sozinho não ajuda o usuário a decidir nada. {@code reasons} carrega a
 * explicação item a item exibida na interface — cada critério que somou ou faltou.
 *
 * @param score          0-100
 * @param matchedSkills  tecnologias da vaga que o currículo tem
 * @param missingSkills  tecnologias da vaga que faltam no currículo
 * @param extraSkills    tecnologias do currículo que a vaga não pede (contexto, não penaliza)
 * @param recommendation faixa qualitativa derivada do score
 */
public record CompatibilityResult(
        Long jobId,
        int score,
        List<String> matchedSkills,
        List<String> missingSkills,
        List<String> extraSkills,
        boolean experienceMatch,
        boolean workModelMatch,
        boolean locationMatch,
        Recommendation recommendation,
        List<Reason> reasons) {

    public enum Recommendation {
        HIGH,
        MEDIUM,
        LOW;

        public static Recommendation fromScore(int score) {
            if (score >= 75) {
                return HIGH;
            }
            return score >= 50 ? MEDIUM : LOW;
        }
    }

    /** Uma linha da explicação. {@code positive=false} vira o aviso "⚠" na interface. */
    public record Reason(String criterion, boolean positive, String text) {

        public static Reason good(String criterion, String text) {
            return new Reason(criterion, true, text);
        }

        public static Reason gap(String criterion, String text) {
            return new Reason(criterion, false, text);
        }
    }
}
