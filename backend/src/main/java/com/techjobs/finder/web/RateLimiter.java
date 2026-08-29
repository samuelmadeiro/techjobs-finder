package com.techjobs.finder.web;

import com.techjobs.finder.config.RateLimitProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Balde de fichas por cliente, compartilhado por todas as instâncias.
 *
 * <p>O estado mora em {@code rate_limit_bucket}, no Postgres. A versão anterior guardava o
 * balde na memória da JVM: com três réplicas, o teto virava o triplo do configurado, e um
 * restart perdoava quem estava abusando. Contador compartilhado é o que faz o limite ser
 * global de verdade.
 *
 * <p>A operação é <strong>um único comando</strong>. Ler o contador e depois gravá-lo abriria
 * a janela clássica: duas requisições simultâneas leem "resta 1 ficha", as duas passam. Aqui
 * o {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE ... RETURNING} calcula a reposição,
 * decide e desconta dentro da mesma instrução — o Postgres serializa os concorrentes na linha
 * do balde, e quem chega depois enxerga o desconto de quem chegou antes.
 *
 * <p>A reposição é contínua: em vez de zerar o contador de tempos em tempos (janela fixa, que
 * permite o dobro do limite na virada), soma-se o tempo decorrido vezes a taxa, com teto na
 * capacidade.
 */
public class RateLimiter {

    /**
     * Cobra uma ficha e devolve o que sobrou; nenhuma linha volta quando não havia ficha.
     *
     * <p>{@code EXCLUDED} não serve no ramo do UPDATE porque o valor depende do que já está
     * gravado — por isso o alias {@code b}, que dá acesso à linha existente.
     */
    private static final String CONSUME = """
            INSERT INTO rate_limit_bucket AS b (bucket_key, tokens, updated_at)
            VALUES (?, ? - 1, NOW())
            ON CONFLICT (bucket_key) DO UPDATE
               SET tokens = LEAST(?, b.tokens + EXTRACT(EPOCH FROM (NOW() - b.updated_at)) * ?) - 1,
                   updated_at = NOW()
             WHERE LEAST(?, b.tokens + EXTRACT(EPOCH FROM (NOW() - b.updated_at)) * ?) >= 1
            RETURNING tokens
            """;

    /** Quanto falta para a próxima ficha, só para informar o {@code Retry-After}. */
    private static final String REMAINING = """
            SELECT LEAST(?, tokens + EXTRACT(EPOCH FROM (NOW() - updated_at)) * ?)
              FROM rate_limit_bucket WHERE bucket_key = ?
            """;

    private final JdbcTemplate jdbc;
    private final RateLimitProperties.Rule rule;

    public RateLimiter(JdbcTemplate jdbc, RateLimitProperties properties) {
        this.jdbc = jdbc;
        this.rule = properties.getResumeUpload();
    }

    /**
     * Consome uma ficha do cliente.
     *
     * @return vazio quando a requisição pode seguir; caso contrário, quanto esperar
     */
    public Optional<Duration> consume(String clientKey) {
        if (!rule.isEnabled()) {
            return Optional.empty();
        }
        double capacity = rule.getCapacity();
        double refillPerSecond = capacity / Math.max(1, rule.getPeriod().toSeconds());

        List<Double> remaining = jdbc.queryForList(CONSUME, Double.class,
                clientKey, capacity, capacity, refillPerSecond, capacity, refillPerSecond);
        if (!remaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(waitTime(clientKey, capacity, refillPerSecond));
    }

    private Duration waitTime(String clientKey, double capacity, double refillPerSecond) {
        Double tokens = jdbc.query(REMAINING,
                rs -> rs.next() ? rs.getDouble(1) : 0d, capacity, refillPerSecond, clientKey);
        double missing = 1 - (tokens == null ? 0d : tokens);
        long seconds = (long) Math.ceil(missing / refillPerSecond);
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    /** Remove baldes sem uso recente. Balde cheio não guarda informação nenhuma. */
    public int purgeIdleBefore(java.time.Instant threshold) {
        return jdbc.update("DELETE FROM rate_limit_bucket WHERE updated_at < ?",
                java.sql.Timestamp.from(threshold));
    }
}
