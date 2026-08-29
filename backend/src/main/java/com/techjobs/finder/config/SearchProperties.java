package com.techjobs.finder.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limites e TTL do fluxo de busca. */
@ConfigurationProperties(prefix = "techjobs.search")
public class SearchProperties {

    private int candidateLimit = 500;
    /**
     * Teto de vagas por resposta. Cem porque é a maior opção que a interface oferece; o
     * serviço aplica {@code min(size, maxPageSize)} mesmo depois da validação do DTO, então
     * nem um cliente fora do navegador consegue pedir mais.
     */
    private int maxPageSize = 100;
    private Duration cacheTtl = Duration.ofMinutes(30);

    /**
     * Enquanto o resultado tiver menos que isto, ele é servido como está e nada é coletado.
     *
     * <p>Dez minutos vem do comportamento real das fontes: o scheduler já varre o feed geral
     * a cada 30 minutos e a varredura profunda a cada 4 horas, então o acervo não muda de
     * forma perceptível em janelas curtas. Valor menor faria a coleta em segundo plano
     * disparar à toa; maior atrasaria demais a chegada de vaga nova para quem busca um filtro
     * específico.
     */
    private Duration freshTtl = Duration.ofMinutes(10);

    /**
     * Até aqui o resultado antigo continua sendo entregue na hora, com atualização disparada
     * em segundo plano. Passado isso, a busca é tratada como nunca coletada — mas continua
     * respondendo com o que houver no banco.
     */
    private Duration staleTtl = Duration.ofMinutes(30);

    // O limite de coletas simultâneas saiu daqui: quem executa passou a ser o worker, e o
    // limite dele é techjobs.scraping.worker.concurrency. Manter os dois só criaria um botão
    // que não faz nada.
    private Duration onDemandBudget = Duration.ofSeconds(20);

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public Duration getFreshTtl() {
        return freshTtl;
    }

    public void setFreshTtl(Duration freshTtl) {
        this.freshTtl = freshTtl;
    }

    public Duration getStaleTtl() {
        return staleTtl;
    }

    public void setStaleTtl(Duration staleTtl) {
        this.staleTtl = staleTtl;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getOnDemandBudget() {
        return onDemandBudget;
    }

    public void setOnDemandBudget(Duration onDemandBudget) {
        this.onDemandBudget = onDemandBudget;
    }
}
