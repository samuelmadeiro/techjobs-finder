package com.techjobs.finder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Sessão autenticada de um usuário.
 *
 * <p>Guarda o <em>hash</em> do token, nunca o token: quem lê o banco não consegue se passar
 * por ninguém. A validade tem duas pontas — {@code expiresAt} (prazo absoluto) e
 * {@code revokedAt} (encerrada por logout ou revogação) — e ambas são verificadas a cada
 * requisição, o que é justamente o que um token autocontido não permitiria fazer sem uma
 * lista de bloqueio à parte.
 */
@Entity
@Table(name = "user_session")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    protected UserSession() {
    }

    public UserSession(AppUser user, String tokenHash, Instant expiresAt, String userAgent) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
    }

    /** Vale agora? Prazo e revogação são checados juntos porque falham juntos. */
    public boolean isUsableAt(Instant moment) {
        return revokedAt == null && expiresAt.isAfter(moment);
    }

    public void revoke(Instant moment) {
        if (revokedAt == null) {
            this.revokedAt = moment;
        }
    }

    public void touch(Instant moment) {
        this.lastSeenAt = moment;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
