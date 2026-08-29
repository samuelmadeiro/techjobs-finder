package com.techjobs.finder.repository;

import com.techjobs.finder.entity.Company;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByNormalizedName(String normalizedName);

    /** Empresas de um lote de ingestão inteiro, em uma consulta. */
    List<Company> findByNormalizedNameIn(Collection<String> normalizedNames);

    /** Empresa e quantas vagas ativas ela tem. Ver a justificativa da projeção nomeada
     * em {@code TechnologyRepository.TechnologyJobCount}. */
    interface CompanyJobCount {
        Long getId();

        String getName();

        long getJobCount();
    }

    /** Empresas que possuem ao menos uma vaga ativa, com a contagem. */
    @Query("""
            SELECT c.id AS id, c.name AS name, COUNT(j.id) AS jobCount
            FROM Job j JOIN j.company c
            WHERE j.active = true
            GROUP BY c.id, c.name
            ORDER BY COUNT(j.id) DESC, c.name ASC
            """)
    List<CompanyJobCount> findActiveCompaniesWithJobCount(Limit limit);
}
