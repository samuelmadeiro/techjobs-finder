package com.techjobs.finder.repository;

import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.dto.job.JobSearchRequest;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.util.Text;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Tradução do filtro para predicados JPA Criteria. Nenhuma concatenação de SQL:
 * todo valor do usuário entra como parâmetro vinculado.
 */
public final class JobSpecifications {

    private JobSpecifications() {
    }

    public static Specification<Job> from(JobSearchFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));

            if (filter.level() != null) {
                // Níveis adjacentes entram no conjunto e são ordenados depois pela relevância.
                predicates.add(root.get("experienceLevel").in(adjacentLevels(filter)));
            }
            if (filter.workModel() != null) {
                predicates.add(root.get("workModel").in(compatibleWorkModels(filter.workModel())));
            }
            if (filter.country() != null) {
                // Duas faixas entram: as vagas daquele país e as que não são de país
                // nenhum (remoto global, "LATAM", "Europe"), que continuam abertas a quem
                // mora lá. Filtrar só pelo código exato esvaziaria a busca — a maior parte
                // do acervo é remoto sem país declarado — e esconderia vaga elegível.
                predicates.add(root.get("countryCode")
                        .in(filter.country(), com.techjobs.finder.service.CountryCatalog.GLOBAL));
            }
            if (filter.location() != null) {
                // location/country guardam o texto original da fonte: compara em minúsculas,
                // sem remover acentos (o normalize é reservado a normalizedTitle).
                String pattern = "%" + filter.location().toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("location")), pattern),
                        cb.like(cb.lower(root.get("country")), pattern),
                        // Vaga remota é candidata a qualquer localização pedida.
                        cb.equal(root.get("workModel"), WorkModel.REMOTE)));
            }
            if (filter.keyword() != null) {
                String normalized = "%" + Text.normalize(filter.keyword()) + "%";
                String raw = "%" + filter.keyword().toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(root.get("normalizedTitle"), normalized),
                        cb.like(cb.lower(root.get("summary")), raw)));
            }
            if (!filter.sources().isEmpty()) {
                predicates.add(root.join("source", JoinType.INNER).get("code").in(filter.sources()));
            }
            if (filter.hasTechnologyCriteria()) {
                var technologies = root.join("technologies", JoinType.INNER);
                predicates.add(technologies.get("slug").in(filter.allTechnologySlugs()));
                if (query != null) {
                    // O join de tecnologia multiplica linhas; distinct evita repetir a mesma vaga.
                    query.distinct(true);
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static List<com.techjobs.finder.entity.ExperienceLevel> adjacentLevels(JobSearchFilter filter) {
        var wanted = filter.level();
        List<com.techjobs.finder.entity.ExperienceLevel> accepted = new ArrayList<>();
        for (var level : com.techjobs.finder.entity.ExperienceLevel.values()) {
            if (level == wanted
                    || level == com.techjobs.finder.entity.ExperienceLevel.UNKNOWN
                    || Math.abs(level.rank() - wanted.rank()) == 1) {
                accepted.add(level);
            }
        }
        return accepted;
    }

    private static List<WorkModel> compatibleWorkModels(WorkModel wanted) {
        return switch (wanted) {
            case REMOTE -> List.of(WorkModel.REMOTE, WorkModel.HYBRID, WorkModel.UNKNOWN);
            case HYBRID -> List.of(WorkModel.HYBRID, WorkModel.REMOTE, WorkModel.UNKNOWN);
            case ONSITE -> List.of(WorkModel.ONSITE, WorkModel.HYBRID, WorkModel.UNKNOWN);
            case UNKNOWN -> List.of(WorkModel.values());
        };
    }


    /**
     * Mesmo filtro, com ordenação aplicada no banco.
     *
     * <p>A ordem entra pela Specification e não por {@code Sort} do Pageable porque
     * {@code sort=date} ordena por {@code COALESCE(published_at, first_seen_at)} — uma
     * expressão, que {@code Sort} não sabe escrever. O Pageable vai sem ordenação para não
     * sobrescrever o que é definido aqui.
     *
     * <p>O critério vem de um enum, nunca de texto do cliente: não existe caminho por onde
     * um valor de query string alcance o {@code ORDER BY}.
     */
    public static Specification<Job> ordered(JobSearchFilter filter,
                                             JobSearchRequest.SortMode sortMode) {
        Specification<Job> base = from(filter);
        return (root, query, cb) -> {
            Predicate predicate = base.toPredicate(root, query, cb);
            // A consulta de contagem passa por aqui também, e ORDER BY em COUNT(*) é erro
            // no PostgreSQL — daí a checagem do tipo de retorno.
            if (query != null && query.getResultType() != Long.class
                    && query.getResultType() != long.class) {
                query.orderBy(orderFor(root, cb, sortMode));
            }
            return predicate;
        };
    }

    /**
     * O id sempre fecha a ordenação. Sem um desempate determinístico, duas vagas com o mesmo
     * valor de ordenação podem trocar de posição entre uma página e a seguinte, e uma delas
     * desaparece da listagem sem nunca ter sido mostrada.
     */
    private static List<jakarta.persistence.criteria.Order> orderFor(
            jakarta.persistence.criteria.Root<Job> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            JobSearchRequest.SortMode sortMode) {
        return switch (sortMode) {
            case DATE -> List.of(
                    cb.desc(cb.coalesce(root.get("publishedAt"), root.get("firstSeenAt"))),
                    cb.desc(root.get("id")));
            case COMPANY -> List.of(
                    cb.asc(cb.lower(root.join("company", JoinType.LEFT).get("name"))),
                    cb.asc(root.get("id")));
            // Relevância é calculada na aplicação: não há coluna para o banco ordenar.
            case RELEVANCE -> List.of(cb.desc(root.get("id")));
        };
    }

    /** Apenas vagas com a tecnologia exata, sem tolerância. Usado em testes e filtros estritos. */
    public static Specification<Job> hasTechnology(String slug) {
        return (root, query, cb) -> {
            var join = root.join("technologies", JoinType.INNER);
            if (query != null) {
                query.distinct(true);
            }
            return cb.equal(join.<Technology>get("slug"), slug);
        };
    }
}
