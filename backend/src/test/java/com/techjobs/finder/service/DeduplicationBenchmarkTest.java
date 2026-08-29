package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.scraper.RawJob;
import com.techjobs.finder.service.JobNormalizer.Normalized;
import com.techjobs.finder.util.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mede a deduplicação no tamanho de uma varredura profunda real e compara com a estratégia
 * anterior, que percorria tudo o que já havia sido mantido para cada candidato.
 *
 * <p>Não é um teste de tempo com limite fixo — máquina de CI oscila. O que ele garante é o
 * que interessa: o resultado das duas estratégias é idêntico, e o número de comparações
 * deixa de crescer com o quadrado do lote. Os tempos vão para o log.
 */
class DeduplicationBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationBenchmarkTest.class);

    /** Ordem de grandeza de uma rodada de {@code harvest} com todas as fontes ligadas. */
    private static final int CANDIDATES = 20_000;

    /** Vagas por empresa: o fator que decide quanto trabalho sobra depois do agrupamento. */
    private static final int JOBS_PER_COMPANY = 25;

    private final DeduplicationService service = new DeduplicationService();

    @Test
    @DisplayName("agrupar por empresa devolve o mesmo resultado com muito menos comparação")
    void bucketedCollapseMatchesQuadraticReference() {
        List<Normalized> batch = batch(CANDIDATES);

        Counter quadraticComparisons = new Counter();
        long quadraticStart = System.nanoTime();
        List<Normalized> expected = quadraticReference(batch, quadraticComparisons);
        Duration quadratic = Duration.ofNanos(System.nanoTime() - quadraticStart);

        long bucketedStart = System.nanoTime();
        List<Normalized> actual = service.collapse(batch);
        Duration bucketed = Duration.ofNanos(System.nanoTime() - bucketedStart);

        log.info("""
                        Deduplicação de {} candidatos ({} vagas por empresa)
                          varredura completa (antes): {} ms, {} comparações
                          agrupado por empresa (depois): {} ms
                          mantidos: {}""",
                CANDIDATES, JOBS_PER_COMPANY,
                quadratic.toMillis(), quadraticComparisons.value,
                bucketed.toMillis(), actual.size());

        assertThat(actual.stream().map(Normalized::fingerprint).toList())
                .isEqualTo(expected.stream().map(Normalized::fingerprint).toList());
    }

    /**
     * Reprodução da estratégia anterior: cada candidato era confrontado com todos os já
     * mantidos, e só depois a comparação era descartada por serem de empresas diferentes.
     */
    private List<Normalized> quadraticReference(List<Normalized> batch, Counter comparisons) {
        List<Normalized> collapsed = service.collapse(List.of());
        List<Normalized> byFingerprint = new ArrayList<>(dedupByFingerprint(batch));
        List<Normalized> result = new ArrayList<>(collapsed);
        for (Normalized candidate : byFingerprint) {
            boolean duplicate = false;
            for (Normalized kept : result) {
                comparisons.value++;
                if (isSameJob(kept, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** O primeiro passo (fingerprint e URL) é igual nas duas estratégias. */
    private List<Normalized> dedupByFingerprint(List<Normalized> batch) {
        java.util.Map<String, Normalized> byFingerprint = new java.util.LinkedHashMap<>();
        for (Normalized candidate : batch) {
            byFingerprint.putIfAbsent(candidate.fingerprint(), candidate);
        }
        return new ArrayList<>(byFingerprint.values());
    }

    private boolean isSameJob(Normalized left, Normalized right) {
        if (left.normalizedCompany() == null || right.normalizedCompany() == null
                || !left.normalizedCompany().equals(right.normalizedCompany())) {
            return false;
        }
        return Text.tokenSimilarity(left.title(), right.title())
                >= DeduplicationService.TITLE_SIMILARITY_THRESHOLD
                && sameLocation(left.location(), right.location());
    }

    private boolean sameLocation(String left, String right) {
        String a = Text.normalize(left);
        String b = Text.normalize(right);
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return true;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private List<Normalized> batch(int size) {
        List<Normalized> batch = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int company = i / JOBS_PER_COMPANY;
            batch.add(normalized("Desenvolvedor Java Pleno " + i, "empresa-" + company, i));
        }
        return batch;
    }

    private Normalized normalized(String title, String company, int index) {
        RawJob raw = new RawJob()
                .setTitle(title)
                .setCompany(company)
                .setUrl("https://exemplo.test/j/" + index)
                .setSourceCode("remoteok");
        return new Normalized(title, Text.normalize(title), company, company, "Remoto", "BR", "BR",
                WorkModel.REMOTE, ExperienceLevel.MID, null, null, null, List.of(), List.of(),
                null, null, null, Set.of(), null, "fp-" + index, raw);
    }

    private static final class Counter {
        private long value;
    }
}
