package com.techjobs.finder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Marca quando uma combinação de filtros foi realmente buscada nas fontes.
 * Enquanto a entrada estiver dentro do TTL, a busca é servida direto do banco.
 */
@Entity
@Table(name = "search_cache_entry")
public class SearchCacheEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String fingerprint;

    @Column(name = "query_text", length = 500)
    private String queryText;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt = Instant.now();

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    protected SearchCacheEntry() {
    }

    public SearchCacheEntry(String fingerprint, String queryText) {
        this.fingerprint = fingerprint;
        this.queryText = queryText;
    }

    public Long getId() {
        return id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getQueryText() {
        return queryText;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    public int getResultCount() {
        return resultCount;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = resultCount;
    }
}
