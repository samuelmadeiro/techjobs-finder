package com.techjobs.finder.dto.resume;

import com.techjobs.finder.entity.TechnologyKind;

/**
 * Habilidade lida do currículo.
 *
 * @param slug  identificador do catálogo; nulo quando a tecnologia ainda não é catalogada
 * @param known {@code false} = termo reconhecido no texto mas fora do catálogo, portanto
 *              exibido ao usuário sem entrar no cálculo de compatibilidade
 */
public record ResumeSkillResponse(
        String slug,
        String name,
        TechnologyKind kind,
        int occurrences,
        boolean known) {
}
