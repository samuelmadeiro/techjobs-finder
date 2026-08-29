package com.techjobs.finder.repository;

import com.techjobs.finder.entity.Job;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

    /** Vagas ativas por código de país, para o catálogo de países. */
    @Query("""
            SELECT j.countryCode AS code, COUNT(j) AS jobCount
              FROM Job j
             WHERE j.active = true
             GROUP BY j.countryCode
            """)
    List<CountryJobCount> countActiveByCountry();

    interface CountryJobCount {
        String getCode();

        long getJobCount();
    }


    /** Projeção de id, usada para selecionar candidatos sem hidratar a entidade inteira. */
    interface JobId {
        Long getId();
    }

    /**
     * Ids das vagas que casam com o filtro, até o teto informado.
     *
     * <p>A busca precisa de dois passos — selecionar candidatos e depois carregar os
     * detalhes com entity graph. Trazer entidades completas no primeiro passo seria
     * pagar a hidratação de milhares de linhas duas vezes; aqui vem só a chave.
     */
    default List<Long> findCandidateIds(Specification<Job> specification, int limit) {
        return findBy(specification, query -> query.as(JobId.class).limit(limit).all())
                .stream()
                .map(JobId::getId)
                .toList();
    }

    @EntityGraph(attributePaths = {"company", "source", "technologies"})
    Optional<Job> findWithDetailsById(Long id);

    /**
     * Vagas já conhecidas de um lote inteiro, em uma consulta.
     *
     * <p>A ingestão pergunta "essa vaga já existe?" para cada candidato. Perguntando uma
     * por vez, uma varredura de milhares de vagas vira milhares de idas ao banco dentro
     * da mesma transação; aqui o lote inteiro é resolvido de uma vez.
     */
    List<Job> findByFingerprintIn(Collection<String> fingerprints);

    /**
     * Os candidatos vêm de {@code findAll(Specification, Pageable)} (herdado de
     * {@code JpaSpecificationExecutor}), sem entity graph: {@code LIMIT} junto de join de
     * coleção cortaria linhas no meio da coleção. Os detalhes são carregados em seguida aqui.
     */
    @EntityGraph(attributePaths = {"company", "source", "technologies"})
    List<Job> findWithDetailsByIdIn(Collection<Long> ids);

    /**
     * Vagas ativas das empresas do lote, usadas na checagem de duplicata por similaridade
     * de título.
     *
     * <p>Recebe o conjunto de empresas e não uma só: a ingestão precisa dessa lista para
     * cada candidato novo, e consultar por empresa dentro do laço repetia a mesma consulta
     * uma vez por vaga da mesma empresa.
     */
    @Query("""
            SELECT j FROM Job j
            WHERE j.active = true AND j.company.id IN :companyIds
            """)
    List<Job> findActiveByCompanyIdIn(@Param("companyIds") Collection<Long> companyIds);

    @Modifying
    @Query("UPDATE Job j SET j.active = false WHERE j.active = true AND j.lastSeenAt < :threshold")
    int deactivateStale(@Param("threshold") Instant threshold);

    @Modifying
    @Query("DELETE FROM Job j WHERE j.active = false AND j.lastSeenAt < :threshold")
    int purgeInactive(@Param("threshold") Instant threshold);

    long countByActiveTrue();
}
