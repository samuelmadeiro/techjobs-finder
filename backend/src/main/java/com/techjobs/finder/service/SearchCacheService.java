package com.techjobs.finder.service;

import com.techjobs.finder.config.SearchProperties;
import com.techjobs.finder.dto.job.JobSearchFilter;
import com.techjobs.finder.entity.SearchCacheEntry;
import com.techjobs.finder.repository.SearchCacheEntryRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controla quando vale a pena chamar as fontes de novo. Guarda, por combinação de
 * filtros, o instante da última coleta; dentro do TTL a busca é servida do banco.
 */
@Service
public class SearchCacheService {

    private final SearchCacheEntryRepository repository;
    private final SearchProperties properties;

    public SearchCacheService(SearchCacheEntryRepository repository, SearchProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Quão atual está o resultado desta combinação de filtros. */
    public enum Freshness {
        /** Coletado há pouco: serve como está, sem acionar fonte nenhuma. */
        FRESH,
        /** Envelhecendo: serve como está agora e atualiza em segundo plano. */
        STALE,
        /** Nunca coletado, ou velho demais para contar como coletado. */
        MISS
    }

    /** Estado do resultado e quando ele foi montado. */
    public record CollectionState(Freshness freshness, Instant collectedAt) {
    }

    /**
     * Classifica o resultado sem tocar em fonte externa.
     *
     * <p>Três estados em vez de "fresco ou não" porque a diferença importa para o usuário:
     * um resultado de quinze minutos atrás é bom o suficiente para ser mostrado na hora,
     * mesmo que valha a pena atualizá-lo depois. Antes, esse caso obrigava a esperar a coleta.
     */
    @Transactional(readOnly = true)
    public CollectionState stateOf(JobSearchFilter filter) {
        Instant now = Instant.now();
        return repository.findByFingerprint(filter.fingerprint())
                .map(SearchCacheEntry::getExecutedAt)
                .map(collectedAt -> {
                    if (collectedAt.isAfter(now.minus(properties.getFreshTtl()))) {
                        return new CollectionState(Freshness.FRESH, collectedAt);
                    }
                    if (collectedAt.isAfter(now.minus(properties.getStaleTtl()))) {
                        return new CollectionState(Freshness.STALE, collectedAt);
                    }
                    return new CollectionState(Freshness.MISS, collectedAt);
                })
                .orElseGet(() -> new CollectionState(Freshness.MISS, null));
    }

    /**
     * Tenta assumir a coleta desta combinação. Só um chamador consegue, mesmo com várias
     * instâncias: quem decide é um upsert condicional no PostgreSQL, não um lock em memória.
     *
     * @param forced quando verdadeiro, ignora o intervalo mínimo — é o {@code refresh=true}
     *               do cliente. A serialização continua valendo: de dois pedidos forçados
     *               simultâneos, apenas um coleta, porque o primeiro já grava o instante
     *               atual e o segundo não encontra nada mais antigo que ele.
     * @return {@code true} quando este chamador é o responsável por coletar agora
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimRefresh(JobSearchFilter filter, boolean forced) {
        Instant now = Instant.now();
        Instant threshold = forced ? now : now.minus(properties.getFreshTtl());

        return repository.claimForRefresh(filter.fingerprint(), filter.toQueryText(),
                threshold, now) > 0;
    }

    /**
     * Registra a coleta em transação própria: mesmo que a requisição falhe adiante,
     * a marca de "já consultei essa combinação" não se perde e evita martelar a fonte.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCollected(JobSearchFilter filter, int resultCount) {
        SearchCacheEntry entry = repository.findByFingerprint(filter.fingerprint())
                .orElseGet(() -> new SearchCacheEntry(filter.fingerprint(), filter.toQueryText()));
        entry.setExecutedAt(Instant.now());
        entry.setResultCount(resultCount);
        repository.save(entry);
    }

    @Transactional
    public int evictOlderThan(Instant threshold) {
        return repository.deleteOlderThan(threshold);
    }
}
