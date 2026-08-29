package com.techjobs.finder.dto.resume;

/**
 * Resposta do envio de currículo.
 *
 * <p>Não devolve mais token: a identidade passou a viver no cookie de sessão, escrito pelo
 * servidor. O corpo carrega apenas o perfil extraído — o que a tela precisa mostrar.
 */
public record ResumeUploadResponse(ResumeResponse resume) {
}
