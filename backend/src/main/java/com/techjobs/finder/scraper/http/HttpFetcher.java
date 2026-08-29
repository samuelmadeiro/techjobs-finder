package com.techjobs.finder.scraper.http;

import com.techjobs.finder.config.ScraperProperties;
import com.techjobs.finder.exception.ScraperException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cliente HTTP dos scrapers. Concentra as políticas de coleta em um lugar só:
 * robots.txt, rate limit por host, timeout, retry com backoff exponencial e
 * validação de destino contra SSRF.
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final HttpClient httpClient;
    private final ScraperProperties properties;
    private final RobotsTxtService robotsTxtService;
    private final HostRateLimiter rateLimiter;

    public HttpFetcher(HttpClient scraperHttpClient,
                       ScraperProperties properties,
                       RobotsTxtService robotsTxtService,
                       HostRateLimiter rateLimiter) {
        this.httpClient = scraperHttpClient;
        this.properties = properties;
        this.robotsTxtService = robotsTxtService;
        this.rateLimiter = rateLimiter;
    }

    public String get(String source, String url, Map<String, String> headers) {
        URI uri = validate(source, url);
        if (!robotsTxtService.isAllowed(uri)) {
            throw new ScraperException(source, "robots.txt proíbe a coleta de " + uri.getPath());
        }

        RuntimeException lastError = null;
        int attempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                rateLimiter.acquire(uri, source);
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .header("User-Agent", properties.getUserAgent())
                        .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                        .timeout(properties.getReadTimeout())
                        .GET();
                headers.forEach(builder::header);

                HttpResponse<String> response =
                        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 429 || status >= 500) {
                    // Erro transitório: vale nova tentativa.
                    lastError = new ScraperException(source, "HTTP " + status + " em " + uri.getHost());
                    log.warn("Fonte {} respondeu {} (tentativa {}/{})", source, status, attempt, attempts);
                    backoff(attempt);
                    continue;
                }
                if (status == 403 || status == 401) {
                    throw new ScraperException(source, "Acesso bloqueado pela fonte (HTTP " + status + ")");
                }
                if (status >= 400) {
                    throw new ScraperException(source, "HTTP " + status + " ao consultar a fonte");
                }
                return response.body();
            } catch (IOException e) {
                lastError = new ScraperException(source, "Falha de rede: " + e.getMessage(), e);
                log.warn("Fonte {} falhou na rede (tentativa {}/{}): {}", source, attempt, attempts, e.getMessage());
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ScraperException(source, "Coleta interrompida", e);
            }
        }
        throw lastError != null ? lastError : new ScraperException(source, "Falha desconhecida na coleta");
    }

    public String get(String source, String url) {
        return get(source, url, Map.of());
    }

    private void backoff(int attempt) {
        long millis = properties.getRetryBackoff().toMillis() * (long) Math.pow(2, attempt - 1d);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Só permite http/https com host público nomeado. Bloqueia localhost, IP literal e
     * qualquer coisa que um dado de fonte externa possa injetar em uma URL de detalhe.
     */
    private URI validate(String source, String url) {
        URI uri;
        try {
            uri = URI.create(url).normalize();
        } catch (IllegalArgumentException e) {
            throw new ScraperException(source, "URL inválida: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new ScraperException(source, "Esquema não permitido: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ScraperException(source, "URL sem host: " + url);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (lowerHost.equals("localhost")
                || lowerHost.endsWith(".localhost")
                || lowerHost.endsWith(".internal")
                || lowerHost.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")
                || lowerHost.contains(":")) {
            throw new ScraperException(source, "Host não permitido para coleta: " + host);
        }
        return uri;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
