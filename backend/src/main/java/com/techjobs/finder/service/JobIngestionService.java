package com.techjobs.finder.service;

import com.techjobs.finder.config.CacheConfig;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.scraper.ScrapeResult;
import com.techjobs.finder.service.JobIngestionWriter.ChunkStats;
import com.techjobs.finder.service.JobNormalizer.Normalized;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Normaliza, deduplica e persiste o resultado bruto dos scrapers.
 * Vaga já conhecida tem apenas {@code lastSeenAt} e campos voláteis atualizados.
 *
 * <p>Este serviço não é transacional: normalização e deduplicação são trabalho de CPU e
 * não têm por que segurar uma conexão do pool. A gravação acontece em sub-lotes, cada um
 * em sua própria transação ({@link JobIngestionWriter}), porque a mesma vaga pode chegar
 * ao mesmo tempo pelo scheduler e por uma busca sob demanda: nesse encontro, a constraint
 * única de {@code fingerprint} rejeita a segunda inserção, e uma transação única para o
 * lote inteiro perderia milhares de vagas por causa de uma.
 */
@Service
public class JobIngestionService {

    private static final Logger log = LoggerFactory.getLogger(JobIngestionService.class);

    /**
     * Vagas por transação. Grande o bastante para as leituras em lote valerem a pena,
     * pequeno o bastante para um conflito custar pouco reprocessamento.
     */
    static final int CHUNK_SIZE = 200;//limitado a 200 por motivos de memoria limitada

    private final JobIngestionWriter writer;
    private final JobNormalizer normalizer;
    private final DeduplicationService deduplicationService;
    private final CacheManager cacheManager;

    public JobIngestionService(JobIngestionWriter writer,
                               JobNormalizer normalizer,
                               DeduplicationService deduplicationService,
                               CacheManager cacheManager) {
        this.writer = writer;
        this.normalizer = normalizer;
        this.deduplicationService = deduplicationService;
        this.cacheManager = cacheManager;
    }

    /** Estatísticas de uma rodada de ingestão, usadas em log e no endpoint de fontes. */
    public record IngestionStats(int received, int afterDedup, int created, int updated,
                                 int skipped) {

        @Contract(" -> new")
        static @NonNull  IngestionStats empty() {
            return new IngestionStats(0, 0, 0, 0, 0);
        }
    }

    public IngestionStats ingest(List<ScrapeResult> results) {
        writer.recordSourceStatus(results);

        List<Normalized> batch = normalize(results);
        if (batch.isEmpty()) {
            return IngestionStats.empty();
        }

        List<Normalized> deduped = deduplicationService.collapse(batch);

        ChunkStats total = ChunkStats.empty();
        for (int from = 0; from < deduped.size(); from += CHUNK_SIZE) {
            List<Normalized> chunk = deduped.subList(from, Math.min(from + CHUNK_SIZE, deduped.size()));
            total = total.plus(persist(chunk));
        }

        IngestionStats stats = new IngestionStats(batch.size(), deduped.size(),
                total.created(), total.updated(), total.skipped());
        log.info("Ingestão concluída: {} recebidas, {} após deduplicação, {} novas, "
                        + "{} atualizadas, {} ignoradas",
                stats.received(), stats.afterDedup(), stats.created(), stats.updated(),
                stats.skipped());

        // Vaga nova muda a contagem por tecnologia e por empresa que o catálogo publica.
        // Só invalida quando algo entrou: atualização de "visto por último" não altera
        // nenhum número exibido, e limpar à toa desperdiçaria a agregação já feita.
        if (stats.created() > 0) {
            evictCatalog();
            // Vaga nova muda o que a busca devolve: as páginas já montadas deixam de valer.
            // Só quando algo entrou — "visto por último" atualizado não altera resultado.
            evict(CacheConfig.SEARCH_CACHE);
        }
        return stats;
    }

    private @NonNull List<Normalized> normalize(@NonNull List<ScrapeResult> results) {
        List<Normalized> batch = new ArrayList<>();
        for (ScrapeResult result : results) {
            if (!result.success()) {
                continue;
            }
            for (RawJob raw : result.jobs()) {
                try {
                    batch.add(normalizer.normalize(raw, result.source()));
                } catch (RuntimeException e) {
                    log.warn("Vaga descartada na normalização (fonte {}): {}",
                            result.source(), e.getMessage());
                }
            }
        }
        return batch;
    }

    /**
     * Grava o sub-lote e, se ele esbarrar em uma gravação concorrente, reprocessa item a
     * item.
     *
     * <p>A exceção só pode ser tratada aqui, fora da transação que falhou: uma vez que o
     * banco recusa a inserção, aquela transação está marcada para rollback e nenhuma outra
     * gravação passaria por ela. Na segunda tentativa a vaga conflitante já está no banco
     * e é reconhecida como existente — o conflito vira atualização, não erro.
     */
    private ChunkStats persist(List<Normalized> chunk) {
        ensureCompanies(chunk);
        try {
            return writer.write(chunk);
        } catch (DataAccessException e) {
            log.info("Conflito ao gravar sub-lote de {} vaga(s); reprocessando individualmente: {}",
                    chunk.size(), rootMessage(e));
            return persistOneByOne(chunk);
        }
    }

    /**
     * Segunda tentativa por empresa criada em paralelo: quando a chamada falha, a linha
     * que faltava foi gravada pela outra transação, então repetir só encontra o que já
     * existe.
     */
    private void ensureCompanies(List<Normalized> chunk) {
        try {
            writer.ensureCompanies(chunk);
        } catch (DataAccessException e) {
            log.debug("Empresa do lote criada em paralelo; relendo: {}", rootMessage(e));
            writer.ensureCompanies(chunk);
        }
    }

    /**
     * Cada vaga em sua própria transação. A que conflitou já está no banco pela mão da
     * outra ingestão e agora é reconhecida como existente; as demais entram normalmente.
     */
    private @NonNull ChunkStats persistOneByOne(@NonNull List<Normalized> chunk) {
        ChunkStats total = ChunkStats.empty();
        for (Normalized candidate : chunk) {
            try {
                total = total.plus(writer.write(List.of(candidate)));
            } catch (DataAccessException first) {
                try {
                    total = total.plus(writer.write(List.of(candidate)));
                } catch (DataAccessException second) {
                    log.warn("Vaga '{}' descartada por conflito persistente: {}",
                            candidate.title(), rootMessage(second));
                    total = total.plus(new ChunkStats(0, 0, 1));
                }
            }
        }
        return total;
    }

    private String rootMessage(@NonNull DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() == null ? e.toString() : cause.getMessage();
    }

    /** Falha ao limpar o cache não pode desfazer uma ingestão bem-sucedida. */
    private void evictCatalog() {
        evict(CacheConfig.CATALOG_CACHE);
    }

    private void evict(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        } catch (RuntimeException e) {
            log.warn("Não foi possível invalidar o cache '{}'", cacheName, e);
        }
    }
}
