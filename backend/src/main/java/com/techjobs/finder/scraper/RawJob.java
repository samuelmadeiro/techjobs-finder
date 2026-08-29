package com.techjobs.finder.scraper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Vaga como saiu da fonte, antes de qualquer normalização.
 * Scrapers produzem {@code RawJob}; o {@code JobNormalizer} converte em entidade.
 */
public class RawJob {

    /** Preenchido pelo orquestrador; o scraper não precisa se preocupar com isso. */
    private String sourceCode;
    private String externalId;
    private String title;
    private String company;
    private String companyWebsite;
    private String location;
    private String country;
    private String workModelHint;
    private String levelHint;
    private String descriptionHtml;
    private String url;
    private String salaryRaw;
    private String benefits;
    private Instant publishedAt;
    private Instant updatedAt;
    private final List<String> tags = new ArrayList<>();

    public String getSourceCode() {
        return sourceCode;
    }

    public RawJob setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
        return this;
    }

    public String getExternalId() {
        return externalId;
    }

    public RawJob setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public String getTitle() {
        return title;
    }

    public RawJob setTitle(String title) {
        this.title = title;
        return this;
    }

    public String getCompany() {
        return company;
    }

    public RawJob setCompany(String company) {
        this.company = company;
        return this;
    }

    public String getCompanyWebsite() {
        return companyWebsite;
    }

    public RawJob setCompanyWebsite(String companyWebsite) {
        this.companyWebsite = companyWebsite;
        return this;
    }

    public String getLocation() {
        return location;
    }

    public RawJob setLocation(String location) {
        this.location = location;
        return this;
    }

    public String getCountry() {
        return country;
    }

    public RawJob setCountry(String country) {
        this.country = country;
        return this;
    }

    public String getWorkModelHint() {
        return workModelHint;
    }

    public RawJob setWorkModelHint(String workModelHint) {
        this.workModelHint = workModelHint;
        return this;
    }

    public String getLevelHint() {
        return levelHint;
    }

    public RawJob setLevelHint(String levelHint) {
        this.levelHint = levelHint;
        return this;
    }

    public String getDescriptionHtml() {
        return descriptionHtml;
    }

    public RawJob setDescriptionHtml(String descriptionHtml) {
        this.descriptionHtml = descriptionHtml;
        return this;
    }

    public String getUrl() {
        return url;
    }

    public RawJob setUrl(String url) {
        this.url = url;
        return this;
    }

    public String getSalaryRaw() {
        return salaryRaw;
    }

    public RawJob setSalaryRaw(String salaryRaw) {
        this.salaryRaw = salaryRaw;
        return this;
    }

    public String getBenefits() {
        return benefits;
    }

    public RawJob setBenefits(String benefits) {
        this.benefits = benefits;
        return this;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public RawJob setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
        return this;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public RawJob setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }

    public List<String> getTags() {
        return tags;
    }

    public RawJob addTags(List<String> values) {
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank()).forEach(tags::add);
        }
        return this;
    }

    /** Uma vaga sem título ou sem URL é inútil e deve ser descartada. */
    public boolean isUsable() {
        return title != null && !title.isBlank() && url != null && !url.isBlank();
    }
}
