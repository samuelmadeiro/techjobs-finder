package com.techjobs.finder.repository;

import com.techjobs.finder.entity.AppUser;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Legado: só a troca do token antigo por uma sessão ainda usa isto. */
    Optional<AppUser> findByAccessToken(String accessToken);

    /**
     * Conta por e-mail, sem diferenciar caixa — o índice único também é sobre
     * {@code lower(email)}, então a busca e a restrição enxergam a mesma coisa.
     */
    Optional<AppUser> findByEmailIgnoreCase(String email);

    /**
     * Remove usuários antigos que não têm mais currículo nenhum.
     *
     * <p>Cada upload sem token cria um usuário. Sem esta limpeza, a tabela cresceria a cada
     * envio anônimo e guardaria tokens válidos para sempre. Usuário com currículo é
     * preservado: quem apaga o currículo é a retenção, e só depois o dono vira órfão.
     */
    @Modifying
    @Query("""
            DELETE FROM AppUser u
            WHERE u.createdAt < :threshold
              AND NOT EXISTS (SELECT 1 FROM Resume r WHERE r.user = u)
            """)
    int deleteAbandonedOlderThan(@Param("threshold") Instant threshold);
}
