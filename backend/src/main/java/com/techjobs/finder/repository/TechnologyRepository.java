package com.techjobs.finder.repository;

import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.entity.TechnologyKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TechnologyRepository extends JpaRepository<Technology, Long> {

    Optional<Technology> findBySlug(String slug);

    List<Technology> findBySlugIn(Collection<String> slugs);

    /**
     * Tecnologia e quantas vagas ativas a citam.
     *
     * <p>Projeção nomeada em vez de {@code Object[]}: com o array, quem consome escreve
     * {@code (Long) row[3]} e qualquer mudança na ordem do SELECT vira
     * {@code ClassCastException} em produção, sem aviso do compilador.
     */
    interface TechnologyJobCount {
        String getSlug();

        String getName();

        TechnologyKind getKind();

        long getJobCount();
    }

    /** Tecnologias com contagem de vagas ativas, para montar os filtros do frontend. */
    @Query("""
            SELECT t.slug AS slug, t.name AS name, t.kind AS kind, COUNT(j.id) AS jobCount
            FROM Job j JOIN j.technologies t
            WHERE j.active = true
            GROUP BY t.slug, t.name, t.kind
            ORDER BY COUNT(j.id) DESC, t.name ASC
            """)
    List<TechnologyJobCount> findWithActiveJobCount();

    @Query("""
            SELECT t.slug AS slug, t.name AS name, t.kind AS kind, COUNT(j.id) AS jobCount
            FROM Job j JOIN j.technologies t
            WHERE j.active = true AND t.kind = :kind
            GROUP BY t.slug, t.name, t.kind
            ORDER BY COUNT(j.id) DESC, t.name ASC
            """)
    List<TechnologyJobCount> findWithActiveJobCountByKind(@Param("kind") TechnologyKind kind);
}
