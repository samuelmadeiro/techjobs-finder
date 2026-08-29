package com.techjobs.finder.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Política do worker de scraping: quantos ao mesmo tempo, por quanto tempo, e quantas
 * tentativas.
 *
 * <p>Os padrões são conservadores de propósito. O gargalo não é CPU nem banco: são as fontes
 * externas, que já respondem a um limitador por host ({@code HostRateLimiter}) e devolvem 429
 * quando pressionadas. Aumentar concorrência aqui não coleta mais rápido — coleta mais
 * bloqueios.
 */
@ConfigurationProperties(prefix = "techjobs.scraping.worker")
public class ScrapingWorkerProperties {

    /** Desligado nos testes, onde quem dispara o ciclo é a chamada explícita. */
    private boolean enabled = true;

    /**
     * Coletas simultâneas nesta instância.
     *
     * <p>Dois porque é o que o {@code SearchRefreshService} já usava, e cada coleta abre até
     * {@code techjobs.scraper.parallelism} (6) conexões para as fontes: são até 12 requisições
     * externas em voo por instância.
     */
    private int concurrency = 2;

    /** Intervalo entre varreduras da fila. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** Espera antes do primeiro ciclo, para a subida da aplicação não competir com coleta. */
    private Duration initialDelay = Duration.ofSeconds(10);

    /**
     * Tentativas por job, contadas no claim.
     *
     * <p>Três, e não mais: as fontes que falham em sequência (429, robots.txt fechado, API
     * fora) não se recuperam em minutos, e o scheduler já reenfileira o feed geral a cada 30
     * minutos. Insistir além disso pressiona quem já está reclamando.
     */
    private int maxAttempts = 3;

    /**
     * Espera antes da segunda tentativa. Trinta segundos porque o próprio scraper já tem
     * retry interno ({@code techjobs.scraper.max-retries} com backoff de 800 ms): quando a
     * execução inteira falha, o problema não é um pacote perdido.
     */
    private Duration initialBackoff = Duration.ofSeconds(30);

    /** Teto do backoff exponencial. */
    private Duration maxBackoff = Duration.ofMinutes(10);

    /**
     * Espera mínima quando a fonte pede explicitamente (429 com {@code Retry-After} maior que
     * o backoff calculado, ou 429 sem cabeçalho).
     */
    private Duration throttledBackoff = Duration.ofMinutes(5);

    /**
     * Folga da lease sobre o orçamento da coleta.
     *
     * <p>A lease precisa durar mais que a execução, senão outro worker recupera um job que
     * está rodando normalmente e a coleta acontece duas vezes. Sobre o orçamento ainda há
     * ingestão (normalização, deduplicação e gravação em lotes), que em varredura profunda
     * chega a milhares de vagas — daí a folga ser minutos, não segundos.
     */
    private Duration leaseMargin = Duration.ofMinutes(5);

    /**
     * Além disto o worker cancela a própria execução, sem esperar a lease vencer. É o teto
     * de tempo que um job ocupa uma vaga de concorrência.
     */
    private Duration executionTimeoutMargin = Duration.ofMinutes(2);

    /** Prazo de guarda das linhas terminadas. Depois disso a limpeza diária as remove. */
    private Duration historyRetention = Duration.ofDays(7);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public Duration getThrottledBackoff() {
        return throttledBackoff;
    }

    public void setThrottledBackoff(Duration throttledBackoff) {
        this.throttledBackoff = throttledBackoff;
    }

    public Duration getLeaseMargin() {
        return leaseMargin;
    }

    public void setLeaseMargin(Duration leaseMargin) {
        this.leaseMargin = leaseMargin;
    }

    public Duration getExecutionTimeoutMargin() {
        return executionTimeoutMargin;
    }

    public void setExecutionTimeoutMargin(Duration executionTimeoutMargin) {
        this.executionTimeoutMargin = executionTimeoutMargin;
    }

    public Duration getHistoryRetention() {
        return historyRetention;
    }

    public void setHistoryRetention(Duration historyRetention) {
        this.historyRetention = historyRetention;
    }
}
