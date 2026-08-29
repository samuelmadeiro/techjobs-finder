package com.techjobs.finder.service;

import com.techjobs.finder.config.AuthProperties;
import com.techjobs.finder.entity.AppUser;
import com.techjobs.finder.entity.UserSession;
import com.techjobs.finder.exception.InvalidCredentialsException;
import com.techjobs.finder.repository.AppUserRepository;
import com.techjobs.finder.repository.UserSessionRepository;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.util.Text;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida da sessão: abrir, validar, encerrar.
 *
 * <p>O token que vai para o navegador é gerado aqui e nunca mais existe do lado do
 * servidor — o banco guarda apenas o hash. Consequência prática: não há como recuperar uma
 * sessão perdida, só abrir outra, que é o comportamento correto.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 256 bits: token de portador precisa ser inviável de adivinhar. */
    private static final int TOKEN_BYTES = 32;

    private final AppUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public AuthenticationService(AppUserRepository userRepository,
                                 UserSessionRepository sessionRepository,
                                 PasswordEncoder passwordEncoder,
                                 AuthProperties properties) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** Token em claro (só existe nesta resposta) e a identidade que ele passa a valer. */
    public record IssuedSession(String token, AuthenticatedUser user, Duration maxAge) {
    }

    // ------------------------------------------------------------------ abertura

    /**
     * Conta nova sem credencial. É o que sustenta o uso sem cadastro: a pessoa envia o
     * currículo e recebe uma sessão, sem inventar senha para um serviço que ainda não sabe
     * se vai usar. Ganhar e-mail e senha depois não troca o id nem perde o que já existe.
     */
    @Transactional
    public IssuedSession openAnonymousSession(String userAgent) {
        AppUser user = userRepository.save(new AppUser(null));
        return issue(user, userAgent);
    }

    /**
     * Vincula credenciais à conta da sessão atual, ou cria uma conta nova quando não há
     * sessão. Nos dois casos devolve uma sessão nova: renovar o token no momento em que o
     * nível de acesso muda é o que impede que um token capturado antes do cadastro
     * continue valendo depois dele.
     */
    @Transactional
    public IssuedSession register(String email, String rawPassword, AuthenticatedUser current,
                                  String userAgent) {
        String normalized = normalizeEmail(email);
        AppUser user = current == null
                ? new AppUser(null)
                : userRepository.findById(current.id()).orElseGet(() -> new AppUser(null));

        if (user.hasCredentials()) {
            throw new InvalidCredentialsException("Esta conta já possui e-mail e senha.");
        }
        user.setCredentials(normalized, passwordEncoder.encode(rawPassword));
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Índice único em lower(email). A mensagem não diz "e-mail já cadastrado" com
            // todas as letras para não virar um oráculo de quem tem conta aqui.
            log.debug("Cadastro recusado por e-mail duplicado", e);
            throw new InvalidCredentialsException("Não foi possível concluir o cadastro com esses dados.");
        }

        // A sessão anterior morre junto: quem estava usando a conta anônima em outro
        // dispositivo perde o acesso, que é o esperado ao a conta ganhar dono.
        sessionRepository.revokeAllOfUser(user.getId(), Instant.now());
        return issue(user, userAgent);
    }

    /**
     * Autentica por e-mail e senha.
     *
     * <p>Mesma mensagem para e-mail inexistente e senha errada, e o hash é verificado mesmo
     * quando o usuário não existe: sem isso, o tempo de resposta contaria quem tem conta.
     */
    @Transactional
    public IssuedSession login(String email, String rawPassword, String userAgent) {
        Optional<AppUser> found = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .filter(AppUser::hasCredentials);

        String hash = found.map(AppUser::getPasswordHash).orElse(DUMMY_HASH);
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        if (found.isEmpty() || !matches) {
            throw new InvalidCredentialsException("E-mail ou senha inválidos.");
        }
        return issue(found.get(), userAgent);
    }

    /**
     * BCrypt de uma senha que ninguém tem, para o caminho do e-mail inexistente custar o
     * mesmo que o do e-mail existente.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * Ponte para quem ainda tem o token antigo no navegador: troca por uma sessão e
     * aposenta o token. Só a primeira requisição de cada usuário passa por aqui.
     */
    @Transactional
    public Optional<IssuedSession> migrateLegacyToken(String legacyToken, String userAgent) {
        if (Text.blankToNull(legacyToken) == null) {
            return Optional.empty();
        }
        return userRepository.findByAccessToken(legacyToken.trim()).map(user -> {
            user.clearLegacyToken();
            log.info("Token legado do usuário {} trocado por sessão", user.getId());
            return issue(user, userAgent);
        });
    }

    private IssuedSession issue(AppUser user, String userAgent) {
        String token = newToken();
        Instant expiresAt = Instant.now().plus(properties.getAbsoluteTimeout());
        sessionRepository.save(new UserSession(user, hash(token), expiresAt,
                Text.truncate(userAgent, 255)));
        return new IssuedSession(token,
                new AuthenticatedUser(user.getId(), null, user.isAnonymous()),
                properties.getAbsoluteTimeout());
    }

    // ------------------------------------------------------------------ validação

    /**
     * Valida o token de uma requisição.
     *
     * <p>Três checagens, todas do lado do servidor: a sessão existe, não foi revogada e não
     * venceu — nem pelo prazo absoluto nem por inatividade. É o que um token autocontido
     * não entrega sem consultar algo de qualquer jeito.
     */
    @Transactional
    public Optional<AuthenticatedUser> authenticate(String token) {
        if (Text.blankToNull(token) == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return sessionRepository.findByTokenHash(hash(token))
                .filter(session -> session.isUsableAt(now))
                .filter(session -> isActiveEnough(session, now))
                .map(session -> {
                    // Escrita só quando o registro já está velho: ver touchInterval.
                    if (session.getLastSeenAt().plus(properties.getTouchInterval()).isBefore(now)) {
                        session.touch(now);
                    }
                    AppUser user = session.getUser();
                    return new AuthenticatedUser(user.getId(), session.getId(), user.isAnonymous());
                });
    }

    private boolean isActiveEnough(UserSession session, Instant now) {
        return session.getLastSeenAt().plus(properties.getIdleTimeout()).isAfter(now);
    }

    // ------------------------------------------------------------------ encerramento

    /** Logout: encerra apenas a sessão que fez a chamada, não as dos outros dispositivos. */
    @Transactional
    public void logout(AuthenticatedUser current) {
        if (current == null || current.sessionId() == null) {
            return;
        }
        sessionRepository.findById(current.sessionId())
                .ifPresent(session -> session.revoke(Instant.now()));
    }

    // ------------------------------------------------------------------ utilidades

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 do token. Ver a justificativa de não usar hash lento na migration V6. */
    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
