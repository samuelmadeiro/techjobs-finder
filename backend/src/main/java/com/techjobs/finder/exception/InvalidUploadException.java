package com.techjobs.finder.exception;

/** Arquivo de currículo rejeitado na validação de entrada. */
public class InvalidUploadException extends RuntimeException {

    public InvalidUploadException(String message) {
        super(message);
    }
}
