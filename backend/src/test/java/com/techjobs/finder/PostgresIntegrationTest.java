package com.techjobs.finder;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base dos testes de integração: PostgreSQL real com as migrations do Flyway aplicadas,
 * garantindo que entidades e schema continuam compatíveis.
 *
 * <p>Por padrão sobe um container via Testcontainers. Em ambientes onde o daemon do Docker
 * não está acessível de dentro do processo de teste (por exemplo, rodando o Maven já dentro
 * de um container), defina {@code TEST_DATABASE_URL}, {@code TEST_DATABASE_USER} e
 * {@code TEST_DATABASE_PASSWORD} para apontar para um Postgres já em execução.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
public abstract class PostgresIntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");
    private static final PostgreSQLContainer<?> POSTGRES =
            EXTERNAL_URL == null ? new PostgreSQLContainer<>("postgres:16-alpine") : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        }
    }
}
