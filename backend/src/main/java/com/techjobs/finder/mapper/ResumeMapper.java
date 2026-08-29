package com.techjobs.finder.mapper;

import com.techjobs.finder.dto.resume.ResumeResponse;
import com.techjobs.finder.dto.resume.ResumeSkillResponse;
import com.techjobs.finder.entity.Resume;
import com.techjobs.finder.entity.ResumeItem;
import com.techjobs.finder.entity.ResumeItemKind;
import com.techjobs.finder.entity.ResumeSkill;
import com.techjobs.finder.entity.Technology;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Conversão do currículo para DTO.
 *
 * <p>Nada aqui devolve o binário, o texto integral, o checksum ou o dono: são dados
 * pessoais que a interface não precisa e que, uma vez expostos, não dá para recolher.
 */
@Component
public class ResumeMapper {

    public ResumeResponse toResponse(Resume resume) {
        return new ResumeResponse(
                resume.getId(),
                resume.getOriginalFilename(),
                resume.getSizeBytes(),
                resume.getContentType(),
                resume.getCreatedAt(),
                resume.getParseStatus(),
                resume.getParseMessage(),
                resume.getCandidateName(),
                resume.getHeadline(),
                resume.getExperienceLevel(),
                resume.getExperienceYears(),
                resume.getPreferredWorkModel(),
                resume.getLocation(),
                resume.getSkills().stream().map(this::toSkill).toList(),
                itemsOf(resume, ResumeItemKind.EXPERIENCE),
                itemsOf(resume, ResumeItemKind.EDUCATION),
                itemsOf(resume, ResumeItemKind.CERTIFICATION),
                itemsOf(resume, ResumeItemKind.PROJECT));
    }

    private ResumeSkillResponse toSkill(ResumeSkill skill) {
        Technology technology = skill.getTechnology();
        return new ResumeSkillResponse(
                technology == null ? null : technology.getSlug(),
                technology == null ? skill.getLabel() : technology.getName(),
                technology == null ? null : technology.getKind(),
                skill.getOccurrences(),
                technology != null);
    }

    private List<String> itemsOf(Resume resume, ResumeItemKind kind) {
        return resume.getItems().stream()
                .filter(item -> item.getKind() == kind)
                .map(ResumeItem::getText)
                .toList();
    }
}
