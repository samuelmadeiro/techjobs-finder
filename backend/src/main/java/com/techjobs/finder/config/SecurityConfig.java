package com.techjobs.finder.config;

import com.techjobs.finder.dto.ApiResponse;
import com.techjobs.finder.security.SessionAuthenticationFilter;
import com.techjobs.finder.security.ResumeCipher;
import com.techjobs.finder.security.SessionCookies;
import com.techjobs.finder.service.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Regras de acesso da API.
 *
 * <p>Duas decisões que valem explicar:
 *
 * <p><strong>Sessão no banco, não JWT.</strong> Este é um serviço só, que já fala com o
 * Postgres em toda requisição — a vantagem do token autocontido (verificar sem consultar
 * nada) não se aplica. Em compensação, o requisito de encerrar acesso na hora, que existe
 * porque a sessão dá acesso a currículo, exigiria de um JWT uma lista de bloqueio
 * consultada a cada requisição: a mesma consulta que a sessão faz, mais o custo de
 * gerenciar chave, expiração curta e renovação. Sessão opaca é a opção mais simples que
 * atende ao requisito.
 *
 * <p><strong>Sem sessão do servlet.</strong> {@code STATELESS} porque o estado mora na
 * tabela {@code user_session}, compartilhada por todas as instâncias. Nada de
 * {@code HttpSession} em memória, que exigiria afinidade de sessão no balanceador.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({AuthProperties.class, EncryptionProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Custo 10: padrão do Spring, ~50 ms por verificação. Alto o bastante para tornar
        // força bruta cara, baixo o bastante para não virar vetor de negação de serviço no
        // próprio login.
        return new BCryptPasswordEncoder();
    }

    /**
     * Cifra do currículo. Bean único porque guarda as chaves já decodificadas: fazer isso
     * por chamada desperdiçaria trabalho em todo upload.
     */
    @Bean
    public ResumeCipher resumeCipher(EncryptionProperties properties) {
        return new ResumeCipher(properties);
    }

    @Bean
    public SessionCookies sessionCookies(AuthProperties properties) {
        return new SessionCookies(properties);
    }

    @Bean
    public SessionAuthenticationFilter sessionAuthenticationFilter(
            @Lazy AuthenticationService authenticationService,
            SessionCookies cookies,
            AuthProperties properties) {
        return new SessionAuthenticationFilter(authenticationService, cookies, properties);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SessionAuthenticationFilter sessionFilter,
                                           CorsConfigurationSource corsConfigurationSource,
                                           ObjectMapper objectMapper) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // CSRF desligado porque não há cookie enviado em requisição cross-site:
                // o de sessão é SameSite=Lax e as rotas que mudam estado são POST e DELETE.
                // Ver a justificativa completa em SessionCookies.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .anonymous(anonymous -> anonymous.disable())
                .authorizeHttpRequests(auth -> auth
                        // Antes da regra pública de /api/jobs/**: recomendação lê o
                        // currículo de alguém, então precisa saber de quem.
                        .requestMatchers("/api/jobs/recommended").authenticated()
                        // Público: catálogo e busca de vagas são o produto, e exigir conta
                        // para olhar vaga afastaria o usuário antes de ele ver valor.
                        .requestMatchers("/api/jobs/**", "/api/languages", "/api/technologies",
                                "/api/companies", "/api/sources", "/api/countries").permitAll()
                        // Abrir sessão e cadastrar não podem exigir sessão.
                        .requestMatchers("/api/auth/sessions", "/api/auth/users").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        // Currículo, candidatura e dados da conta: só autenticado. A
                        // checagem de dono é por recurso, no serviço.
                        .requestMatchers("/api/resumes/**", "/api/applications/**",
                                "/api/auth/me", "/api/auth/sessions/current").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                write(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Autenticação necessária."))
                        .accessDeniedHandler((request, response, exception) ->
                                write(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                        "Acesso negado.")))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Erro de autenticação também sai no envelope da API: o cliente tem um único formato
     * para ler, e a página de erro padrão do Spring não vaza para dentro do JSON.
     */
    private void write(HttpServletResponse response, ObjectMapper objectMapper, int status,
                       String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(message));
    }
}
