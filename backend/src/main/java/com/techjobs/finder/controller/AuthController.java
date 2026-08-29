package com.techjobs.finder.controller;

import com.techjobs.finder.dto.ApiResponse;
import com.techjobs.finder.dto.auth.AuthDtos.LoginRequest;
import com.techjobs.finder.dto.auth.AuthDtos.RegisterRequest;
import com.techjobs.finder.dto.auth.AuthDtos.SessionResponse;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.security.SessionCookies;
import com.techjobs.finder.service.AuthenticationService;
import com.techjobs.finder.service.AuthenticationService.IssuedSession;
import com.techjobs.finder.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessões e contas.
 *
 * <p>A sessão é um recurso: {@code POST /api/auth/sessions} cria,
 * {@code DELETE /api/auth/sessions/current} encerra. O token nunca aparece no corpo — entra
 * e sai pelo cookie.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final UserService userService;
    private final SessionCookies cookies;

    public AuthController(AuthenticationService authenticationService,
                          UserService userService,
                          SessionCookies cookies) {
        this.authenticationService = authenticationService;
        this.userService = userService;
        this.cookies = cookies;
    }

    /**
     * Abre sessão. Sem corpo, cria uma conta anônima — é o que sustenta usar a aplicação
     * sem cadastro. Com e-mail e senha, autentica uma conta existente.
     */
    @Operation(summary = "Abre uma sessão, com ou sem credenciais")
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SessionResponse>> openSession(
            @RequestBody(required = false) @Valid LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        String userAgent = servletRequest.getHeader("User-Agent");
        IssuedSession issued = request == null
                ? authenticationService.openAnonymousSession(userAgent)
                : authenticationService.login(request.email(), request.password(), userAgent);

        cookies.write(response, issued);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(userService.describe(issued.user()), "Sessão iniciada."));
    }

    /**
     * Vincula e-mail e senha. Feito a partir de uma sessão anônima, a conta é a mesma — o
     * currículo já enviado continua onde está.
     */
    @Operation(summary = "Cadastra credenciais para a conta atual ou para uma conta nova")
    @PostMapping("/users")
    public ResponseEntity<ApiResponse<SessionResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            @AuthenticationPrincipal AuthenticatedUser current,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        IssuedSession issued = authenticationService.register(request.email(), request.password(),
                current, servletRequest.getHeader("User-Agent"));

        cookies.write(response, issued);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(userService.describe(issued.user()), "Cadastro concluído."));
    }

    @Operation(summary = "Identidade da sessão atual")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SessionResponse>> me(
            @AuthenticationPrincipal AuthenticatedUser current) {
        return ResponseEntity.ok(ApiResponse.ok(userService.describe(current)));
    }

    /** Encerra só esta sessão. As abertas em outros dispositivos continuam valendo. */
    @Operation(summary = "Encerra a sessão atual")
    @DeleteMapping("/sessions/current")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal AuthenticatedUser current,
            HttpServletResponse response) {
        authenticationService.logout(current);
        cookies.clear(response);
        return ResponseEntity.ok(ApiResponse.ok(null, "Sessão encerrada."));
    }
}
