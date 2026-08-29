package com.techjobs.finder.scraper;

import java.time.Duration;
import java.util.List;

/** Resultado da coleta de uma única fonte, com sucesso ou falha isolada. */
public record ScrapeResult(
        String source,
        boolean success,
        List<RawJob> jobs,
        String errorMessage,
        Duration elapsed) {

    public static ScrapeResult success(String source, List<RawJob> jobs, Duration elapsed) {
        return new ScrapeResult(source, true, jobs, null, elapsed);
    }

    public static ScrapeResult failure(String source, String errorMessage, Duration elapsed) {
        return new ScrapeResult(source, false, List.of(), errorMessage, elapsed);
    }
}
