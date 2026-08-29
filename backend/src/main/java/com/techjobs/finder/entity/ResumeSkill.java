package com.techjobs.finder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Habilidade reconhecida no currículo.
 *
 * <p>{@code technology} nulo significa termo fora do catálogo: guardamos assim mesmo para
 * mostrar ao usuário o que foi lido e para saber quais tecnologias vale a pena catalogar.
 * Só as que têm {@code technology} entram no cálculo de compatibilidade.
 */
@Entity
@Table(name = "resume_skill")
public class ResumeSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technology_id")
    private Technology technology;

    @Column(nullable = false, length = 120)
    private String label;

    /** Quantas vezes o termo apareceu: proxy simples de ênfase no currículo. */
    @Column(nullable = false)
    private int occurrences = 1;

    protected ResumeSkill() {
    }

    public ResumeSkill(Technology technology, String label, int occurrences) {
        this.technology = technology;
        this.label = label;
        this.occurrences = occurrences;
    }

    public Long getId() {
        return id;
    }

    public Resume getResume() {
        return resume;
    }

    public void setResume(Resume resume) {
        this.resume = resume;
    }

    public Technology getTechnology() {
        return technology;
    }

    public String getLabel() {
        return label;
    }

    public int getOccurrences() {
        return occurrences;
    }
}
