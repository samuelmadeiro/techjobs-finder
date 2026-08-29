package com.techjobs.finder.scraper.http;

import com.techjobs.finder.config.ScraperProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Leitura e avaliação de {@code /robots.txt} por host, com cache em memória.
 * Implementa o subconjunto do padrão que importa aqui: grupos {@code User-agent},
 * diretivas {@code Allow}/{@code Disallow} e casamento pelo prefixo mais longo.
 *
 * <p>Falha de rede ao buscar robots.txt é tratada como "permitido": é o comportamento
 * recomendado pelo padrão quando o arquivo está indisponível (4xx). Em 5xx a coleta é bloqueada.
 */
@Service
public class RobotsTxtService {

    private static final Logger log = LoggerFactory.getLogger(RobotsTxtService.class);

    private final HttpClient httpClient;
    private final ScraperProperties properties;
    private final Map<String, CachedRules> cache = new ConcurrentHashMap<>();

    public RobotsTxtService(HttpClient scraperHttpClient, ScraperProperties properties) {
        this.httpClient = scraperHttpClient;
        this.properties = properties;
    }

    public boolean isAllowed(URI uri) {
        if (!properties.isRespectRobotsTxt()) {
            return true;
        }
        String host = uri.getScheme() + "://" + uri.getAuthority();
        CachedRules rules = cache.compute(host, (key, current) -> {
            if (current != null && !current.isExpired(properties.getRobotsCacheTtl())) {
                return current;
            }
            return fetch(key);
        });
        return rules.allows(path(uri));
    }

    private static String path(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private CachedRules fetch(String host) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(host + "/robots.txt"))
                    .header("User-Agent", properties.getUserAgent())
                    .timeout(properties.getReadTimeout())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                log.warn("robots.txt de {} retornou {}; coleta bloqueada por precaução", host, response.statusCode());
                return CachedRules.denyAll();
            }
            if (response.statusCode() >= 400) {
                return CachedRules.allowAll();
            }
            return CachedRules.parse(response.body(), properties.getUserAgent());
        } catch (IOException e) {
            log.warn("Falha ao ler robots.txt de {}: {}", host, e.getMessage());
            return CachedRules.allowAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CachedRules.denyAll();
        }
    }

    /** Conjunto de regras já compilado para um host. */
    static final class CachedRules {

        private final List<Rule> rules;
        private final Instant fetchedAt = Instant.now();

        private CachedRules(List<Rule> rules) {
            this.rules = rules;
        }

        static CachedRules allowAll() {
            return new CachedRules(List.of());
        }

        static CachedRules denyAll() {
            return new CachedRules(List.of(new Rule("/", false)));
        }

        boolean isExpired(java.time.Duration ttl) {
            return fetchedAt.plus(ttl).isBefore(Instant.now());
        }

        /**
         * Escolhe o grupo de regras mais específico: bloco do nosso user-agent se existir,
         * senão o bloco {@code *}.
         */
        static CachedRules parse(String body, String userAgent) {
            String agentToken = userAgentToken(userAgent);
            List<Rule> specific = new ArrayList<>();
            List<Rule> wildcard = new ArrayList<>();
            boolean inSpecific = false;
            boolean inWildcard = false;
            boolean previousWasAgent = false;

            for (String rawLine : body.split("\\R")) {
                String line = rawLine.split("#", 2)[0].trim();
                if (line.isEmpty()) {
                    continue;
                }
                int separator = line.indexOf(':');
                if (separator < 0) {
                    continue;
                }
                String field = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();

                if ("user-agent".equals(field)) {
                    if (!previousWasAgent) {
                        inSpecific = false;
                        inWildcard = false;
                    }
                    String agent = value.toLowerCase(Locale.ROOT);
                    if ("*".equals(agent)) {
                        inWildcard = true;
                    } else if (agentToken.contains(agent) || agent.contains(agentToken)) {
                        inSpecific = true;
                    }
                    previousWasAgent = true;
                    continue;
                }
                previousWasAgent = false;

                boolean allow = "allow".equals(field);
                if (!allow && !"disallow".equals(field)) {
                    continue;
                }
                // "Disallow:" vazio significa liberar tudo; ignorar a linha tem o mesmo efeito.
                if (!allow && value.isEmpty()) {
                    continue;
                }
                Rule rule = new Rule(value.isEmpty() ? "/" : value, allow);
                if (inSpecific) {
                    specific.add(rule);
                }
                if (inWildcard) {
                    wildcard.add(rule);
                }
            }
            return new CachedRules(specific.isEmpty() ? wildcard : specific);
        }

        private static String userAgentToken(String userAgent) {
            String lowered = userAgent.toLowerCase(Locale.ROOT);
            int slash = lowered.indexOf('/');
            return slash > 0 ? lowered.substring(0, slash) : lowered;
        }

        boolean allows(String path) {
            Rule best = null;
            for (Rule rule : rules) {
                if (rule.matches(path) && (best == null || rule.length() > best.length())) {
                    best = rule;
                }
            }
            return best == null || best.allow();
        }
    }

    /** Uma diretiva Allow/Disallow com suporte a curinga {@code *} e âncora {@code $}. */
    record Rule(String pattern, boolean allow) {

        int length() {
            return pattern.length();
        }

        boolean matches(String path) {
            String raw = pattern;
            boolean anchored = raw.endsWith("$");
            if (anchored) {
                raw = raw.substring(0, raw.length() - 1);
            }
            StringBuilder regex = new StringBuilder("^");
            String[] parts = raw.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    regex.append(".*");
                }
                if (!parts[i].isEmpty()) {
                    regex.append(java.util.regex.Pattern.quote(parts[i]));
                }
            }
            if (anchored) {
                regex.append('$');
            }
            try {
                return java.util.regex.Pattern.compile(regex.toString()).matcher(path).find();
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
