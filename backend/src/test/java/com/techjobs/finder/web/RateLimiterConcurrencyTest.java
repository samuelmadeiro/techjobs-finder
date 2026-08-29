package com.techjobs.finder.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.config.RateLimitProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O limite sob concorrência real, contra o Postgres.
 *
 * <p>O ponto do teste é o que um contador em memória não conseguiria garantir: cem chamadas
 * simultâneas — como as que chegariam a instâncias diferentes atrás de um balanceador —
 * liberando exatamente a capacidade configurada, nem uma a mais.
 */
class RateLimiterConcurrencyTest extends PostgresIntegrationTest {

    private static final int CAPACITY = 5;
    private static final int CONCURRENT_CALLS = 100;

    @Autowired
    private JdbcTemplate jdbc;

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rate_limit_bucket");

        RateLimitProperties properties = new RateLimitProperties();
        properties.getResumeUpload().setCapacity(CAPACITY);
        // Período longo: dentro do teste nenhuma ficha é reposta, então o total liberado é
        // exatamente a capacidade — sem depender de quanto tempo a máquina levou.
        properties.getResumeUpload().setPeriod(Duration.ofHours(1));
        limiter = new RateLimiter(jdbc, properties);
    }

    @Test
    @DisplayName("cem chamadas simultâneas liberam exatamente a capacidade")
    void concurrentCallsRespectTheLimit() throws Exception {
        AtomicInteger liberadas = new AtomicInteger();
        AtomicInteger recusadas = new AtomicInteger();

        // Threads de plataforma e não virtuais: cada chamada segura uma conexão do pool, e
        // virtual thread bloqueada em JDBC não ajuda em nada aqui.
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Callable<Void>> chamadas = java.util.stream.IntStream.range(0, CONCURRENT_CALLS)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        largada.await();
                        Optional<Duration> espera = limiter.consume("user:42");
                        if (espera.isEmpty()) {
                            liberadas.incrementAndGet();
                        } else {
                            recusadas.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = chamadas.stream().map(pool::submit).toList();
            largada.countDown();
            for (Future<Void> future : futures) {
                future.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(liberadas.get()).isEqualTo(CAPACITY);
        assertThat(recusadas.get()).isEqualTo(CONCURRENT_CALLS - CAPACITY);

        // E o balde ficou consistente: sem fichas negativas nem contagem perdida.
        Double restantes = jdbc.queryForObject(
                "SELECT tokens FROM rate_limit_bucket WHERE bucket_key = 'user:42'", Double.class);
        assertThat(restantes).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("baldes de clientes diferentes não se misturam")
    void bucketsAreIndependent() {
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.consume("user:1")).isEmpty();
        }
        assertThat(limiter.consume("user:1")).isPresent();

        // Outro usuário começa com o balde cheio; o limite é por cliente, não global.
        assertThat(limiter.consume("user:2")).isEmpty();
        // E o anônimo é contado por endereço, em espaço de chave separado.
        assertThat(limiter.consume("ip:203.0.113.10")).isEmpty();
    }

    @Test
    @DisplayName("recusa informa quanto esperar")
    void deniedCallReportsRetryAfter() {
        for (int i = 0; i < CAPACITY; i++) {
            limiter.consume("user:9");
        }

        Optional<Duration> espera = limiter.consume("user:9");

        assertThat(espera).isPresent();
        assertThat(espera.get()).isPositive();
        // Uma ficha a cada período/capacidade: 1 hora / 5 = 12 minutos, com arredondamento.
        assertThat(espera.get()).isLessThanOrEqualTo(Duration.ofMinutes(13));
    }

    @Test
    @DisplayName("o tempo repõe fichas sem zerar o balde de uma vez")
    void refillsOverTime() {
        for (int i = 0; i < CAPACITY; i++) {
            limiter.consume("user:7");
        }
        assertThat(limiter.consume("user:7")).isPresent();

        // Envelhece o balde em meia hora: com 5 fichas por hora, repõe ~2,5.
        jdbc.update("UPDATE rate_limit_bucket SET updated_at = NOW() - INTERVAL '30 minutes' "
                + "WHERE bucket_key = 'user:7'");

        assertThat(limiter.consume("user:7")).isEmpty();
        assertThat(limiter.consume("user:7")).isEmpty();
        // A terceira não cabe: a reposição é proporcional ao tempo, não um reset.
        assertThat(limiter.consume("user:7")).isPresent();
    }

    @Test
    @DisplayName("limpeza remove baldes parados")
    void purgesIdleBuckets() {
        limiter.consume("user:3");
        jdbc.update("UPDATE rate_limit_bucket SET updated_at = NOW() - INTERVAL '2 days'");

        int removidos = limiter.purgeIdleBefore(Instant.now().minus(Duration.ofDays(1)));

        assertThat(removidos).isEqualTo(1);
    }
}
