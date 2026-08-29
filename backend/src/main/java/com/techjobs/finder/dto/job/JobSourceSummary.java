package com.techjobs.finder.dto.job;

/** Origem da vaga. {@code url} é sempre o link original, nunca reescrito. */
public record JobSourceSummary(String code, String name, String url) {
}
