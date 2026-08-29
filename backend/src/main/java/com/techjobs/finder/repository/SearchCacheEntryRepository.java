package com.techjobs.finder.repository;

import com.techjobs.finder.entity.SearchCacheEntry;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SearchCacheEntryRepository extends JpaRepository<SearchCacheEntry, Long> {

    Optional<SearchCacheEntry> findByFingerprint(String fingerprint);

    @Modifying
    @Query("DELETE FROM SearchCacheEntry e WHERE e.executedAt < :threshold")
    int deleteOlderThan(@Param("threshold") Instant threshold);

    /**
     * Toma para si o direito de coletar esta combinação de filtros.
     *
     * <p>É o que impede a debandada: cem requisições simultâneas do mesmo filtro executam
     * este comando, o PostgreSQL serializa na linha, e <strong>apenas uma</strong> encontra
     * {@code executed_at} antigo o bastante. As outras recebem 0 e desistem de coletar — sem
     * lock em memória, portanto válido com qualquer número de instâncias.
     *
     * <p>Um comando só, e nativo, por dois motivos. O {@code ON CONFLICT} resolve no mesmo
     * lugar os dois casos — combinação nunca vista e combinação já registrada — sem depender
     * de exceção: capturar violação de unicidade marcaria a transação como rollback-only e o
     * commit falharia mesmo com a exceção tratada. E marcar o instante <em>antes</em> de
     * coletar transforma a linha em reserva: se a coleta falhar, a próxima tentativa só
     * acontece depois do intervalo, o que protege a fonte de tentativas em sequência.
     *
     * @param threshold só assume quem encontrar coleta anterior mais antiga que isto
     * @return 1 quando este chamador é o responsável pela coleta; 0 quando outro já assumiu
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO search_cache_entry (fingerprint, query_text, executed_at, result_count)
            VALUES (:fingerprint, :queryText, :now, 0)
            ON CONFLICT (fingerprint) DO UPDATE SET executed_at = :now
            WHERE search_cache_entry.executed_at < :threshold
            """)
    int claimForRefresh(@Param("fingerprint") String fingerprint,
                        @Param("queryText") String queryText,
                        @Param("threshold") Instant threshold,
                        @Param("now") Instant now);
}
