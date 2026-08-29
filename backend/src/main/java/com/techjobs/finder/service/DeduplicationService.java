package com.techjobs.finder.service;

import com.techjobs.finder.entity.Job;
import com.techjobs.finder.service.JobNormalizer.Normalized;
import com.techjobs.finder.util.Text;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Remove vagas repetidas dentro de um mesmo lote e detecta se um item já existe no banco.
 *
 * <p>Três critérios, do mais forte para o mais fraco:
 * <ol>
 *   <li>fingerprint idêntico (empresa + título + localização normalizados);</li>
 *   <li>URL canônica idêntica (mesmo anúncio republicado);</li>
 *   <li>mesma empresa e títulos com similaridade acima do limiar.</li>
 * </ol>
 */
@Service
public class DeduplicationService {

    /** Acima disso dois títulos da mesma empresa são considerados a mesma vaga. */
    static final double TITLE_SIMILARITY_THRESHOLD = 0.85;

    /**
     * Colapsa duplicatas do lote coletado, preferindo o registro mais informativo
     * (mais tecnologias detectadas e, em empate, com data de publicação).
     */
    public List<Normalized> collapse(List<Normalized> batch) {
        Map<String, Normalized> byFingerprint = new LinkedHashMap<>();
        Map<String, String> urlToFingerprint = new LinkedHashMap<>();

        for (Normalized candidate : batch) {
            String canonicalUrl = canonicalUrl(candidate.raw().getUrl());
            String key = candidate.fingerprint();

            String existingKeyForUrl = urlToFingerprint.get(canonicalUrl);
            if (existingKeyForUrl != null) {
                key = existingKeyForUrl;
            }

            Normalized current = byFingerprint.get(key);
            if (current == null) {
                byFingerprint.put(key, candidate);
                urlToFingerprint.put(canonicalUrl, key);
            } else if (isRicher(candidate, current)) {
                byFingerprint.put(key, candidate);
            }
        }

        return collapseBySimilarity(byFingerprint.values());
    }

    /**
     * Terceiro critério: mesma empresa e títulos parecidos.
     *
     * <p>Comparar cada candidato com todos os já mantidos é quadrático, e a varredura
     * profunda traz dezenas de milhares de vagas por rodada. Como {@link #isSameJob}
     * exige empresa idêntica, comparar fora da empresa é trabalho garantidamente inútil:
     * o índice por empresa restringe a comparação a quem pode casar, sem mudar em nada o
     * resultado.
     */
    private List<Normalized> collapseBySimilarity(Collection<Normalized> candidates) {
        Map<String, List<Normalized>> keptByCompany = new HashMap<>();
        List<Normalized> result = new ArrayList<>();

        for (Normalized candidate : candidates) {
            String company = candidate.normalizedCompany();
            if (company == null) {
                // Sem empresa não há como afirmar que é a mesma vaga; mantém.
                result.add(candidate);
                continue;
            }
            List<Normalized> peers = keptByCompany.computeIfAbsent(company, key -> new ArrayList<>());
            if (peers.stream().noneMatch(kept -> isSameJob(kept, candidate))) {
                peers.add(candidate);
                result.add(candidate);
            }
        }
        return result;
    }

    /** Duplicata já persistida, procurada entre as vagas ativas da mesma empresa. */
    public Optional<Job> findExistingDuplicate(Normalized candidate, List<Job> sameCompanyJobs) {
        return sameCompanyJobs.stream()
                .filter(job -> Text.tokenSimilarity(job.getTitle(), candidate.title())
                        >= TITLE_SIMILARITY_THRESHOLD)
                .filter(job -> sameLocation(job.getLocation(), candidate.location()))
                .findFirst();
    }

    private boolean isSameJob(Normalized left, Normalized right) {
        if (left.normalizedCompany() == null || right.normalizedCompany() == null) {
            return false;
        }
        if (!left.normalizedCompany().equals(right.normalizedCompany())) {
            return false;
        }
        return Text.tokenSimilarity(left.title(), right.title()) >= TITLE_SIMILARITY_THRESHOLD
                && sameLocation(left.location(), right.location());
    }

    /** Localização ausente em um dos lados não impede o casamento. */
    private boolean sameLocation(String left, String right) {
        String a = Text.normalize(left);
        String b = Text.normalize(right);
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return true;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private boolean isRicher(Normalized candidate, Normalized current) {
        int candidateScore = candidate.technologySlugs().size();
        int currentScore = current.technologySlugs().size();
        if (candidateScore != currentScore) {
            return candidateScore > currentScore;
        }
        boolean candidateHasDate = candidate.raw().getPublishedAt() != null;
        boolean currentHasDate = current.raw().getPublishedAt() != null;
        return candidateHasDate && !currentHasDate;
    }

    /** Descarta query string, fragmento e barra final, que variam entre republicações. */
    static String canonicalUrl(String url) {
        if (url == null) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim()).normalize();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return host + path;
        } catch (RuntimeException e) {
            return url.trim().toLowerCase(Locale.ROOT);
        }
    }
}
