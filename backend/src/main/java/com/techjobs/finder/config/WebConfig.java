package com.techjobs.finder.config;

import com.techjobs.finder.web.RateLimitInterceptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS e limite de uso.
 *
 * <p>O CORS deixou de ser configurado por {@code WebMvcConfigurer} e virou um
 * {@code CorsConfigurationSource}: o Spring Security roda antes do MVC, e a configuração
 * feita no MVC não vale para requisições barradas na cadeia de filtros — a resposta 401
 * sairia sem os cabeçalhos e o navegador mostraria erro de CORS no lugar do erro real.
 */
@Configuration
@Import(RateLimitConfig.class)
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(@Value("${techjobs.cors.allowed-origins}") List<String> allowedOrigins,
                     RateLimitInterceptor rateLimitInterceptor) {
        this.allowedOrigins = allowedOrigins;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Origens explícitas, nunca "*": com credenciais liberadas, curinga é proibido
        // pela especificação e seria um convite a qualquer site ler a API autenticado.
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-Resume-Token"));
        // O cookie de sessão só viaja em requisição cross-origin se isto estiver ligado.
        // Em produção o nginx serve tudo na mesma origem e nada disso é exercitado; existe
        // para o caso de o frontend ser publicado em outro host.
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("Retry-After", "Location"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * Só o upload de currículo tem limite: é a rota mais cara do sistema — grava arquivo,
     * cria usuário e extrai texto.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/resumes");
    }
}
