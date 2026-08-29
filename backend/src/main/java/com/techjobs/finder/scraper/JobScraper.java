package com.techjobs.finder.scraper;

import com.techjobs.finder.dto.job.JobSearchFilter;
import java.util.List;

/**
 * Contrato de uma fonte de vagas. Para adicionar um site novo basta criar uma
 * implementação anotada com {@code @Component}: o orquestrador a descobre sozinho.
 *
 * <p>Implementações devem lançar {@code ScraperException} em falha e nunca retornar
 * {@code null}. Uma fonte fora do ar não pode derrubar as demais.
 */
public interface JobScraper {

    /** Código estável da fonte; precisa bater com {@code job_source.code} no banco. */
    String getSource();

    /** Nome legível exibido no frontend. */
    String getDisplayName();

    /** Domínio base, usado para checagem de robots.txt e rate limiting por host. */
    String getBaseUrl();

    /** Executa a busca na fonte e devolve as vagas cruas encontradas. */
    List<RawJob> search(JobSearchFilter filter);

    /**
     * Varredura profunda, usada pelo agendador para encher a base independentemente do que
     * os usuários pesquisam. Deve paginar o máximo que a fonte permitir, dentro dos limites
     * de {@code techjobs.scraper.harvest}.
     *
     * <p>O padrão é uma busca sem filtro; sobrescreva quando a fonte suportar paginação.
     */
    default List<RawJob> harvest() {
        return search(JobSearchFilter.empty());
    }

    /** Permite desligar um scraper em runtime sem removê-lo do classpath. */
    default boolean isEnabled() {
        return true;
    }
}
