package com.techjobs.finder.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contratos da autenticação.
 *
 * <p>Nenhuma resposta devolve o token: ele vai no cookie {@code HttpOnly}, e repeti-lo no
 * corpo daria ao JavaScript da página exatamente o que o cookie existe para esconder.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * @param email    identificador da conta
     * @param password mínimo de 10 caracteres — comprimento é o que mais atrapalha ataque
     *                 de força bruta; regras de "um número e um símbolo" empurram para
     *                 senhas curtas e previsíveis
     */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 10, max = 128) String password) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    /**
     * Quem está autenticado agora.
     *
     * @param userId    id da conta, útil para o cliente correlacionar dados que já tem
     * @param anonymous conta sem e-mail e senha; o frontend usa para oferecer o cadastro
     * @param email     nulo enquanto anônima
     */
    public record SessionResponse(Long userId, boolean anonymous, String email) {
    }
}
