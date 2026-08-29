package com.techjobs.finder.dto.resume;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.ParseStatus;
import com.techjobs.finder.entity.WorkModel;
import java.time.Instant;
import java.util.List;

/**
 * Perfil estruturado extraído do currículo.
 *
 * <p>Não expõe o binário, o texto integral, o caminho de armazenamento nem o dono:
 * apenas o que a interface precisa mostrar e o que alimenta o matching.
 */
public record ResumeResponse(
        Long id,
        String filename,
        long sizeBytes,
        String contentType,
        Instant uploadedAt,
        ParseStatus parseStatus,
        String parseMessage,
        String candidateName,
        String headline,
        ExperienceLevel experienceLevel,
        Integer experienceYears,
        WorkModel preferredWorkModel,
        String location,
        List<ResumeSkillResponse> skills,
        List<String> experiences,
        List<String> education,
        List<String> certifications,
        List<String> projects) {
}
