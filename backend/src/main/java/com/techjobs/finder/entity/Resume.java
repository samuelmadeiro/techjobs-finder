package com.techjobs.finder.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Currículo enviado pelo usuário, com o binário original e o perfil estruturado
 * extraído dele.
 *
 * <p>O arquivo e o texto integral ficam em {@link ResumeContent}, tabela à parte: ler o
 * perfil nunca arrasta megabytes junto. Nenhum endpoint devolve o binário, nada é escrito
 * no disco e o {@code DELETE} apaga o conteúdo em cascata.
 */
@Entity
@Table(name = "resume")
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** Nome informado pelo usuário. Guardado só para exibição, nunca usado como caminho. */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "candidate_name", length = 160)
    private String candidateName;

    @Column(length = 500)
    private String headline;

    @Enumerated(EnumType.STRING)
    @Column(name = "experience_level", nullable = false, length = 20)
    private ExperienceLevel experienceLevel = ExperienceLevel.UNKNOWN;

    @Column(name = "experience_years")
    private Integer experienceYears;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_work_model", length = 20)
    private WorkModel preferredWorkModel;

    @Column(length = 255)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private ParseStatus parseStatus = ParseStatus.PENDING;

    @Column(name = "parse_message", length = 500)
    private String parseMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /*
     * Set e não List nas duas coleções: com dois List (bags), buscar skills e items no
     * mesmo entity graph estoura MultipleBagFetchException. LinkedHashSet preserva a
     * ordem do @OrderBy, então nada se perde na troca.
     */
    @OneToMany(mappedBy = "resume", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("occurrences DESC, label ASC")
    private Set<ResumeSkill> skills = new LinkedHashSet<>();

    @OneToMany(mappedBy = "resume", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("kind ASC, position ASC")
    private Set<ResumeItem> items = new LinkedHashSet<>();

    protected Resume() {
    }

    public Resume(AppUser user, String originalFilename, String contentType, long sizeBytes,
                  String checksum) {
        this.user = user;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getCandidateName() {
        return candidateName;
    }

    public void setCandidateName(String candidateName) {
        this.candidateName = candidateName;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public ExperienceLevel getExperienceLevel() {
        return experienceLevel;
    }

    public void setExperienceLevel(ExperienceLevel experienceLevel) {
        this.experienceLevel = experienceLevel;
    }

    public Integer getExperienceYears() {
        return experienceYears;
    }

    public void setExperienceYears(Integer experienceYears) {
        this.experienceYears = experienceYears;
    }

    public WorkModel getPreferredWorkModel() {
        return preferredWorkModel;
    }

    public void setPreferredWorkModel(WorkModel preferredWorkModel) {
        this.preferredWorkModel = preferredWorkModel;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public ParseStatus getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(ParseStatus parseStatus) {
        this.parseStatus = parseStatus;
    }

    public String getParseMessage() {
        return parseMessage;
    }

    public void setParseMessage(String parseMessage) {
        this.parseMessage = parseMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<ResumeSkill> getSkills() {
        return skills;
    }

    public Set<ResumeItem> getItems() {
        return items;
    }

    public void replaceSkills(List<ResumeSkill> replacements) {
        skills.clear();
        if (replacements != null) {
            replacements.forEach(skill -> {
                skill.setResume(this);
                skills.add(skill);
            });
        }
    }

    public void replaceItems(List<ResumeItem> replacements) {
        items.clear();
        if (replacements != null) {
            replacements.forEach(item -> {
                item.setResume(this);
                items.add(item);
            });
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
