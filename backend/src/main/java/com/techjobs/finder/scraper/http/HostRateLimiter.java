package com.techjobs.finder.scraper.http;

import com.techjobs.finder.config.ScraperProperties;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Garante intervalo mínimo entre requisições ao mesmo host, mesmo com scrapers
 * rodando em paralelo. Educação básica com quem hospeda os dados.
 */
@Component
public class HostRateLimiter {

    private final ScraperProperties properties;
    private final Map<String, HostState> states = new ConcurrentHashMap<>();

    public HostRateLimiter(ScraperProperties properties) {//get
        this.properties = properties;
    }

    /** Bloqueia a thread atual até que seja permitido chamar o host de novo. */
    public void acquire(URI uri) throws InterruptedException {
        acquire(uri, null);
    }

    /**
     * @param source código da fonte, usado para aplicar um intervalo próprio quando
     *               o site exige mais folga que o padrão global.
     */
    public void acquire(URI uri, String source) throws InterruptedException {
        long intervalMillis = properties.requestIntervalFor(source).toMillis();
        if (intervalMillis <= 0) {
            return;
        }
        HostState state = states.computeIfAbsent(uri.getHost(), key -> new HostState());
        long waitMillis;
        state.lock.lock();
        try {
            long now = System.currentTimeMillis();
            long earliest = state.lastRequestAt + intervalMillis;
            waitMillis = Math.max(0, earliest - now);
            state.lastRequestAt = Math.max(now, earliest);
        } finally {
            state.lock.unlock();
        }
        if (waitMillis > 0) {
            Thread.sleep(waitMillis);
        }
    }

    private static final class HostState {
        private final ReentrantLock lock = new ReentrantLock();
        private long lastRequestAt;
    }
}
