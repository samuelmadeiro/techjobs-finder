package com.techjobs.finder.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração de rede e política de coleta dos scrapers. */
@ConfigurationProperties(prefix = "techjobs.scraper")
public class ScraperProperties {

    private boolean enabled = true;
    private String userAgent = "TechJobsFinder/0.1";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(15);
    private int maxRetries = 2;
    private Duration retryBackoff = Duration.ofMillis(800);
    private Duration minRequestInterval = Duration.ofMillis(1200);
    private int maxResultsPerSource = 200;
    private boolean respectRobotsTxt = true;
    private Duration robotsCacheTtl = Duration.ofHours(6);
    private int parallelism = 4;
    private Harvest harvest = new Harvest();
    private Map<String, SourceConfig> sources = new HashMap<>();

    /** Limites da varredura profunda agendada, bem mais altos que os da busca do usuário. */
    public static class Harvest {
        private boolean enabled = true;
        private int maxResultsPerSource = 5000;
        private int maxPages = 25;
        /** Abaixo desse total de vagas ativas, a varredura roda logo na subida da aplicação. */
        private long bootstrapThreshold = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxResultsPerSource() {
            return maxResultsPerSource;
        }

        public void setMaxResultsPerSource(int maxResultsPerSource) {
            this.maxResultsPerSource = maxResultsPerSource;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public long getBootstrapThreshold() {
            return bootstrapThreshold;
        }

        public void setBootstrapThreshold(long bootstrapThreshold) {
            this.bootstrapThreshold = bootstrapThreshold;
        }
    }

    public Harvest getHarvest() {
        return harvest;
    }

    public void setHarvest(Harvest harvest) {
        this.harvest = harvest;
    }

    /** Ajustes por fonte, chaveados pelo {@code code} do scraper. */
    public static class SourceConfig {
        private boolean enabled = true;
        /** Sobrepõe o intervalo global entre requisições. Fontes mais sensíveis pedem mais. */
        private Duration minRequestInterval;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getMinRequestInterval() {
            return minRequestInterval;
        }

        public void setMinRequestInterval(Duration minRequestInterval) {
            this.minRequestInterval = minRequestInterval;
        }
    }

    public boolean isSourceEnabled(String code) {
        SourceConfig config = sources.get(code);
        return config == null || config.isEnabled();
    }

    /** Intervalo mínimo entre requisições para a fonte, caindo no valor global se não houver. */
    public Duration requestIntervalFor(String code) {
        SourceConfig config = code == null ? null : sources.get(code);
        if (config != null && config.getMinRequestInterval() != null) {
            return config.getMinRequestInterval();
        }
        return minRequestInterval;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public Duration getMinRequestInterval() {
        return minRequestInterval;
    }

    public void setMinRequestInterval(Duration minRequestInterval) {
        this.minRequestInterval = minRequestInterval;
    }

    public int getMaxResultsPerSource() {
        return maxResultsPerSource;
    }

    public void setMaxResultsPerSource(int maxResultsPerSource) {
        this.maxResultsPerSource = maxResultsPerSource;
    }

    public boolean isRespectRobotsTxt() {
        return respectRobotsTxt;
    }

    public void setRespectRobotsTxt(boolean respectRobotsTxt) {
        this.respectRobotsTxt = respectRobotsTxt;
    }

    public Duration getRobotsCacheTtl() {
        return robotsCacheTtl;
    }

    public void setRobotsCacheTtl(Duration robotsCacheTtl) {
        this.robotsCacheTtl = robotsCacheTtl;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public Map<String, SourceConfig> getSources() {
        return sources;
    }

    public void setSources(Map<String, SourceConfig> sources) {
        this.sources = sources;
    }
}
