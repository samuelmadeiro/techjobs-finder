package com.techjobs.finder.exception;

/**
 * Credencial recusada. Vira HTTP 401.
 *
 * <p>A mensagem é sempre genérica: distinguir "e-mail não existe" de "senha errada"
 * entrega ao atacante metade do trabalho.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
