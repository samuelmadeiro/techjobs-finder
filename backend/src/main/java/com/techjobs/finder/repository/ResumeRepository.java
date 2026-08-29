package com.techjobs.finder.repository;

import com.techjobs.finder.entity.Resume;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    /**
     * Carrega o perfil estruturado sem tocar em {@code fileData} nem em
     * {@code extractedText}, que são {@code LAZY}: o binário só é lido quando alguém
     * pede explicitamente, e nenhum endpoint pede.
     */
    @EntityGraph(attributePaths = {"skills", "skills.technology", "items"})
    Optional<Resume> findWithProfileById(Long id);

    /**
     * Currículo mais recente do usuário.
     *
     * <p>Sem entity graph de propósito: {@code findFirst} vira {@code LIMIT 1} no SQL, e um
     * limite junto de join de coleção cortaria a coleção no meio. O perfil completo vem
     * depois, por {@link #findWithProfileById}.
     */
    Optional<Resume> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Apaga currículos além do prazo de retenção.
     *
     * <p>Delete em massa: as tabelas filhas ({@code resume_content}, {@code resume_skill},
     * {@code resume_item}) têm {@code ON DELETE CASCADE} no banco, então saem junto sem
     * carregar entidade nenhuma para a memória.
     */
    @Modifying
    @Query("DELETE FROM Resume r WHERE r.createdAt < :threshold")
    int deleteOlderThan(@Param("threshold") Instant threshold);
}
