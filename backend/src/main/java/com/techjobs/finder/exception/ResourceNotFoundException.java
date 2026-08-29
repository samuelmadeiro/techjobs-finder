package com.techjobs.finder.exception;

/** Recurso inexistente. Vira HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super("%s não encontrado(a): %s".formatted(resource, id));
    }
}
