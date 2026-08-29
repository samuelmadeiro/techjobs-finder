package com.techjobs.finder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Dono de um currículo. A aplicação ainda não tem autenticação: a identidade é um
 * token opaco gerado pelo servidor e guardado pelo navegador, nunca uma senha.
 * Quando o login for adicionado, esta entidade ganha as credenciais sem quebrar
 * os relacionamentos existentes.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Legado: token permanente que identificava o usuário antes de existirem sessões.
     * Serve só de credencial de migração e é apagado assim que a conta abre uma sessão.
     */
    @Column(name = "access_token", unique = true, length = 64)
    private String accessToken;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(length = 255)
    private String email;

    /**
     * BCrypt, ou nulo enquanto a conta for anônima.
     *
     * <p>Nulo não é "senha vazia": é conta sem credencial, que só pode ser acessada pela
     * sessão já aberta naquele navegador. Por isso {@link #hasCredentials()} existe — sem
     * ele, um login com senha em branco poderia casar com contas anônimas.
     */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AppUser() {
    }

    public AppUser(String accessToken) {
        this.accessToken = accessToken;
    }

    public Long getId() {
        return id;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /** Vincula credenciais a uma conta que já existe, sem trocar o id nem perder currículo. */
    public void setCredentials(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public boolean hasCredentials() {
        return passwordHash != null && email != null;
    }

    /** Conta ainda sem e-mail e senha: existe só enquanto o navegador guardar a sessão. */
    public boolean isAnonymous() {
        return !hasCredentials();
    }

    /**
     * Aposenta a credencial de migração assim que a conta ganha uma sessão de verdade.
     * Deixá-la viva manteria um token permanente válido em paralelo à sessão que expira.
     */
    public void clearLegacyToken() {
        this.accessToken = null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
