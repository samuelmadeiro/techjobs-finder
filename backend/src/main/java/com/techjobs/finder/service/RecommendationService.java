package com.techjobs.finder.service;

import com.techjobs.finder.dto.recommendation.CompatibilityResult;
import com.techjobs.finder.entity.Job;
import com.techjobs.finder.entity.Resume;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.service.ResumeMatchingService.ResumeProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Aplica o currículo do usuário sobre um conjunto de vagas já selecionado.
 *
 * <p>Fica separado da busca de propósito: a busca sabe encontrar e ordenar vagas por
 * aderência ao filtro; a recomendação sabe compará-las com uma pessoa. Trocar o
 * algoritmo de matching não toca no código de busca.
 */
@Service
public class RecommendationService {

    private final ResumeService resumeService;
    private final ResumeMatchingService matchingService;

    public RecommendationService(ResumeService resumeService, ResumeMatchingService matchingService) {
        this.resumeService = resumeService;
        this.matchingService = matchingService;
    }

    /** Perfil pronto para comparação, ou vazio quando a sessão não tem currículo. */
    public Optional<ResumeProfile> profileFor(AuthenticatedUser current) {
        return resumeService.currentResume(current)
                .filter(this::isUsable)
                .map(ResumeProfile::of);
    }

    /** Currículo sem nenhuma skill reconhecida não gera comparação honesta. */
    private boolean isUsable(Resume resume) {
        return resume.getSkills().stream().anyMatch(skill -> skill.getTechnology() != null);
    }

    /** Compatibilidade de cada vaga, indexada por id. */
    public Map<Long, CompatibilityResult> scoreAll(ResumeProfile profile, List<Job> jobs) {
        Map<Long, CompatibilityResult> results = new HashMap<>(jobs.size());
        for (Job job : jobs) {
            results.put(job.getId(), matchingService.match(profile, job));
        }
        return results;
    }

    public CompatibilityResult score(ResumeProfile profile, Job job) {
        return matchingService.match(profile, job);
    }
}
