package com.techjobs.finder.security;

import com.techjobs.finder.config.AuthProperties;
import com.techjobs.finder.service.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Traduz o cookie de sessão em identidade para o resto da aplicação.
 *
 * <p>É o único lugar do sistema que decide quem é o usuário. Nenhum controller lê
 * cabeçalho, corpo ou parâmetro para descobrir isso — o que chega neles já foi validado
 * contra o banco aqui.
 *
 * <p>Requisição sem cookie válido simplesmente segue sem identidade: quem exige
 * autenticação é a regra de acesso da rota, não este filtro. Assim a busca continua
 * pública e a rota de currículo continua fechada, sem duas listas para manter em sincronia.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    /** Cabeçalho da era pré-sessão. Aceito só para trocar por uma sessão de verdade. */
    public static final String LEGACY_TOKEN_HEADER = "X-Resume-Token";

    private final AuthenticationService authenticationService;
    private final SessionCookies cookies;
    private final AuthProperties properties;

    public SessionAuthenticationFilter(AuthenticationService authenticationService,
                                       SessionCookies cookies,
                                       AuthProperties properties) {
        this.authenticationService = authenticationService;
        this.cookies = cookies;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<AuthenticatedUser> user = readCookie(request)
                .flatMap(authenticationService::authenticate);

        if (user.isEmpty()) {
            user = migrateLegacyToken(request, response);
        }
        user.ifPresent(authenticated -> authenticate(request, authenticated));

        try {
            chain.doFilter(request, response);
        } finally {
            // O contexto é por thread e a thread volta para o pool: deixá-lo preenchido
            // faria a próxima requisição herdar a identidade da anterior.
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<AuthenticatedUser> migrateLegacyToken(HttpServletRequest request,
                                                           HttpServletResponse response) {
        String legacy = request.getHeader(LEGACY_TOKEN_HEADER);
        if (legacy == null || legacy.isBlank()) {
            return Optional.empty();
        }
        return authenticationService.migrateLegacyToken(legacy, request.getHeader("User-Agent"))
                .map(issued -> {
                    // O navegador antigo sai desta requisição já com o cookie novo.
                    cookies.write(response, issued);
                    return issued.user();
                });
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] present = request.getCookies();
        if (present == null) {
            return Optional.empty();
        }
        return Arrays.stream(present)
                .filter(cookie -> properties.getCookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private void authenticate(HttpServletRequest request, AuthenticatedUser user) {
        // Sem papéis: o sistema não tem administrador nem perfis. A autoridade única
        // marca "sessão válida"; quem é dono do quê é decidido por recurso, no serviço.
        var authentication = new UsernamePasswordAuthenticationToken(user, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_USER")));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
