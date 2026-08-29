package com.techjobs.finder.exception;

import com.techjobs.finder.dto.ApiResponse;
import com.techjobs.finder.dto.FieldIssue;
import com.techjobs.finder.security.ResumeDecryptionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Tradução central de exceções para o envelope {@link ApiResponse}.
 *
 * <p>Erros de validação levam a lista de campos em {@code errors}; os demais deixam
 * {@code errors} ausente. Em todos os casos {@code data} é nulo e {@code message} traz o
 * texto exibível. O cliente sempre lê o mesmo formato.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidFilterException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidFilter(InvalidFilterException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ex.getMessage(),
                List.of(new FieldIssue(ex.getField(), ex.getMessage()))));
    }

    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        List<FieldIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldIssue(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("Parâmetros de busca inválidos.", issues));
    }

    /**
     * Cabeçalho ou parte do multipart obrigatória que não veio — por exemplo
     * {@code /api/jobs/recommended} sem {@code X-Resume-Token}. É erro do cliente,
     * não falha do servidor.
     */
    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingInput(Exception ex) {
        String message = ex instanceof MissingRequestHeaderException header
                ? "Cabeçalho obrigatório ausente: %s.".formatted(header.getHeaderName())
                : "Envie o arquivo no campo 'file'.";
        return ResponseEntity.badRequest().body(ApiResponse.fail(message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("Parâmetro '%s' com formato inválido.".formatted(ex.getName())));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("Recurso não encontrado."));
    }

    /** Currículo inválido, grande demais ou de tipo não aceito. */
    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidUpload(InvalidUploadException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.fail(ex.getMessage()));
    }

    /** Estouro do limite do multipart antes de o controller ser chamado. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail("Arquivo maior que o limite permitido."));
    }

    /** Credencial recusada: mesma resposta para e-mail inexistente e senha errada. */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(ex.getMessage()));
    }

    /** Teto de requisições estourado: o cliente sabe quanto esperar, sem detalhe interno. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
                .body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(ScraperException.class)
    public ResponseEntity<ApiResponse<Void>> handleScraper(ScraperException ex) {
        log.error("Falha de scraping propagada da fonte {}", ex.getSource(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail("Não foi possível consultar uma das fontes de vagas."));
    }

    /**
     * Conteúdo cifrado que não abriu. É falha de configuração de chave ou de integridade do
     * armazenamento — nunca culpa da requisição —, então vira 500 com mensagem neutra. O
     * motivo real (chave ausente, tag inválida) fica no log, sem o conteúdo.
     */
    @ExceptionHandler(ResumeDecryptionException.class)
    public ResponseEntity<ApiResponse<Void>> handleDecryption(ResumeDecryptionException ex) {
        log.error("Falha ao decifrar conteúdo de currículo: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("Não foi possível ler o currículo armazenado."));
    }

    /** Fallback: mensagem genérica ao cliente, stacktrace apenas no log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erro inesperado em {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("Erro interno ao processar a requisição."));
    }
}
