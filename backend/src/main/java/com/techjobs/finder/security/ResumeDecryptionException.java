package com.techjobs.finder.security;

/**
 * Conteúdo do currículo não pôde ser decifrado: chave ausente, chave errada ou bytes
 * adulterados. Vira HTTP 500 com mensagem genérica — é falha de configuração ou de
 * integridade do armazenamento, nunca culpa da requisição.
 */
public class ResumeDecryptionException extends RuntimeException {

    public ResumeDecryptionException(String message) {
        super(message);
    }
}
