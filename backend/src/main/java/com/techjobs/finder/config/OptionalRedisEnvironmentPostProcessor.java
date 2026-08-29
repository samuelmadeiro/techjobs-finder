package com.techjobs.finder.config;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import reactor.util.annotation.NonNull;

/**
 * Torna o Redis realmente opcional.
 *
 * <p>{@code spring.data.redis.host} vem de {@code ${REDIS_HOST:}}: sem a variável, o valor
 * é string vazia — e o autoconfigure do Spring Boot, ao ver a propriedade presente, monta
 * o {@code LettuceConnectionFactory} e falha na subida com {@code 'host' must not be
 * empty}. Ou seja: a aplicação não subia justamente no modo "sem Redis" que a configuração
 * promete.
 *
 * <p>Deixar o host cair no padrão {@code localhost} não resolveria: a fábrica de conexões
 * existiria, o indicador de saúde do Redis entraria junto e {@code /actuator/health}
 * reportaria DOWN por causa de um cache que a aplicação escolheu não usar. Por isso o
 * autoconfigure inteiro sai de cena quando não há host.
 */
public class OptionalRedisEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String HOST_PROPERTY = "spring.data.redis.host";
    private static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";
    private static final String REDIS_AUTOCONFIGURATION =
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration";

    @Override
    public void postProcessEnvironment(@NonNull ConfigurableEnvironment environment,
                                                SpringApplication application) {
        String host = environment.getProperty(HOST_PROPERTY, "");//Verifica que
        if (!host.isBlank()) {
            return;
        }

        String current = environment.getProperty(EXCLUDE_PROPERTY, "");
        if (current.contains(REDIS_AUTOCONFIGURATION)) {
            return;
        }
        String merged = current.isBlank()
                ? REDIS_AUTOCONFIGURATION
                : current + "," + REDIS_AUTOCONFIGURATION;

        Map<String, Object> properties = new HashMap<>();
        properties.put(EXCLUDE_PROPERTY, merged);
        environment.getPropertySources()
                .addFirst(new MapPropertySource("techjobs-optional-redis", properties));
    }
}
