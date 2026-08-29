package com.techjobs.finder.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limites de uso por cliente. Hoje só o upload de currículo precisa de um. */
@ConfigurationProperties(prefix = "techjobs.rate-limit")
public class RateLimitProperties {

    private final Rule resumeUpload = new Rule();

    public Rule getResumeUpload() {
        return resumeUpload;
    }

    /** Balde de fichas: {@code capacity} envios, repostos ao longo de {@code period}. */
    public static class Rule {

        private boolean enabled = true;

        /** Rajada tolerada: quantos envios seguidos passam antes de o balde esvaziar. */
        private int capacity = 5;

        /** Tempo para repor o balde inteiro. */
        private Duration period = Duration.ofMinutes(10);

        /**
         * Por quanto tempo um balde parado continua guardado antes de a limpeza diária
         * removê-lo. Balde cheio não guarda informação: só ocupa linha.
         */
        private Duration retainIdleFor = Duration.ofDays(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }

        public Duration getRetainIdleFor() {
            return retainIdleFor;
        }

        public void setRetainIdleFor(Duration retainIdleFor) {
            this.retainIdleFor = retainIdleFor;
        }
    }
}
