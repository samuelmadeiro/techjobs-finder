package com.techjobs.finder.repository;

import com.techjobs.finder.entity.UserSession;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /**
     * Sessão pelo hash do token, com o usuário junto.
     *
     * <p>Entity graph porque toda requisição autenticada precisa dos dois: sem ele seriam
     * duas consultas por requisição, em vez de uma.
     */
    @EntityGraph(attributePaths = "user")
    Optional<UserSession> findByTokenHash(String tokenHash);

    /** Encerra todas as sessões de um usuário — troca de senha, conta comprometida. */
    @Modifying
    @Query("""
            UPDATE UserSession s SET s.revokedAt = :moment
            WHERE s.user.id = :userId AND s.revokedAt IS NULL
            """)
    int revokeAllOfUser(@Param("userId") Long userId, @Param("moment") Instant moment);

    /**
     * Remove sessões que não servem mais para nada.
     *
     * <p>Sessão vencida não autentica ninguém, mas continua ocupando espaço e guardando o
     * user agent de quem a abriu — dado de quem não precisa mais ser guardado.
     */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") Instant threshold);
}
