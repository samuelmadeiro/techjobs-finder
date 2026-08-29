package com.techjobs.finder.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tempo de vida de cada cache, ajustável sem recompilar. */
@Component
@ConfigurationProperties(prefix = "techjobs.cache")
public class CacheProperties {

    /** Usado por qualquer cache sem TTL próprio em {@link #ttls}. */
    private Duration defaultTtl = Duration.ofMinutes(10);

    /** TTL por nome de cache. */
    private Map<String, Duration> ttls = new LinkedHashMap<>();

    /**
     * Prefixo das chaves no Redis. Isola esta aplicação de qualquer outra que
     * compartilhe a mesma instância.
     */
    private String keyPrefix = "techjobs";

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Map<String, Duration> getTtls() {
        return ttls;
    }

    public void setTtls(Map<String, Duration> ttls) {
        this.ttls = ttls;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration ttlFor(String cacheName) {
        return ttls.getOrDefault(cacheName, defaultTtl);
    }
}
