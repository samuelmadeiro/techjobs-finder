package com.techjobs.finder.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.util.annotation.NonNull;

@Configuration
public class HttpClientConfig {

    /**
     * Cliente HTTP compartilhado pelos scrapers. Não segue redirect para host arbitrário
     * automaticamente sem validação: a checagem de host é feita em {@code HttpFetcher}.
     */
    @Bean
    public @NonNull HttpClient scraperHttpClient(@NonNull ScraperProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Bean
    public @NonNull Duration scraperReadTimeout(@NonNull ScraperProperties properties) {
        return properties.getReadTimeout();
    }
}
