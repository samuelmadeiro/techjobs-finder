package com.techjobs.finder.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração do refresh periódico e da limpeza de vagas antigas. */
@ConfigurationProperties(prefix = "techjobs.scheduler")
public class SchedulerProperties {

    private boolean enabled = true;
    private Duration staleAfter = Duration.ofDays(14);
    private Duration purgeAfter = Duration.ofDays(60);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public Duration getPurgeAfter() {
        return purgeAfter;
    }

    public void setPurgeAfter(Duration purgeAfter) {
        this.purgeAfter = purgeAfter;
    }
}
