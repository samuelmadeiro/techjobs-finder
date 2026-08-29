package com.techjobs.finder.config;

import com.techjobs.finder.web.ClientKeyResolver;
import com.techjobs.finder.web.RateLimitInterceptor;
import com.techjobs.finder.web.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Peças do limite de uso, declaradas aqui em vez de descobertas por {@code @Component}.
 *
 * <p>{@code WebConfig} depende do interceptor, e {@code @WebMvcTest} carrega configurações
 * mas não varre componentes: com as peças espalhadas em {@code @Component}, todo teste de
 * controller subia sem o interceptor e o contexto quebrava. Declaradas em uma configuração,
 * elas acompanham quem precisa delas.
 *
 * <p>{@code ConditionalOnMissingBean} no resolvedor de identidade deixa a troca pronta:
 * quando existir autenticação, uma implementação que devolva o id do usuário substitui a
  atual sem tocar em nada aqui.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    @ConditionalOnMissingBean(ClientKeyResolver.class)
    public ClientKeyResolver clientKeyResolver() {
        return new ClientKeyResolver.SessionOrAddress();
    }

    @Bean
    public RateLimiter rateLimiter(JdbcTemplate jdbcTemplate, RateLimitProperties properties) {
        return new RateLimiter(jdbcTemplate, properties);
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(RateLimiter rateLimiter,
                                                     ClientKeyResolver clientKeyResolver) {
        return new RateLimitInterceptor(rateLimiter, clientKeyResolver);
    }
}
