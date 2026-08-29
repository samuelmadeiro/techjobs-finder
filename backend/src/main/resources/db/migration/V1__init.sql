-- Esquema inicial do TechJobs Finder.

CREATE TABLE job_source (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(60)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    base_url    VARCHAR(500) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMPTZ,
    last_status VARCHAR(30),
    last_error  VARCHAR(1000)
);

CREATE TABLE company (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL UNIQUE,
    website         VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE technology (
    id   BIGSERIAL PRIMARY KEY,
    slug VARCHAR(80)  NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    kind VARCHAR(20)  NOT NULL
);

CREATE TABLE job (
    id               BIGSERIAL PRIMARY KEY,
    source_id        BIGINT       NOT NULL REFERENCES job_source (id),
    company_id       BIGINT       REFERENCES company (id),
    external_id      VARCHAR(255),
    fingerprint      VARCHAR(64)  NOT NULL UNIQUE,
    title            VARCHAR(500) NOT NULL,
    normalized_title VARCHAR(500) NOT NULL,
    location         VARCHAR(255),
    country          VARCHAR(120),
    work_model       VARCHAR(20)  NOT NULL,
    experience_level VARCHAR(20)  NOT NULL,
    summary          VARCHAR(2000),
    url              VARCHAR(1000) NOT NULL,
    salary_raw       VARCHAR(255),
    benefits         VARCHAR(1000),
    published_at     TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    first_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    active           BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_job_active_published ON job (active, published_at DESC);
CREATE INDEX idx_job_level ON job (experience_level);
CREATE INDEX idx_job_work_model ON job (work_model);
CREATE INDEX idx_job_source ON job (source_id);
CREATE INDEX idx_job_company ON job (company_id);
CREATE INDEX idx_job_last_seen ON job (last_seen_at);
CREATE UNIQUE INDEX uk_job_source_external ON job (source_id, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE job_technology (
    job_id        BIGINT NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    technology_id BIGINT NOT NULL REFERENCES technology (id),
    PRIMARY KEY (job_id, technology_id)
);

CREATE INDEX idx_job_technology_tech ON job_technology (technology_id);

-- Registro de quando cada combinação de filtros foi buscada nas fontes (TTL de cache).
CREATE TABLE search_cache_entry (
    id           BIGSERIAL PRIMARY KEY,
    fingerprint  VARCHAR(64) NOT NULL UNIQUE,
    query_text   VARCHAR(500),
    executed_at  TIMESTAMPTZ NOT NULL,
    result_count INTEGER     NOT NULL DEFAULT 0
);

INSERT INTO job_source (code, name, base_url) VALUES
    ('remotive',       'Remotive',         'https://remotive.com'),
    ('arbeitnow',      'Arbeitnow',        'https://www.arbeitnow.com'),
    ('remoteok',       'RemoteOK',         'https://remoteok.com'),
    ('weworkremotely', 'We Work Remotely', 'https://weworkremotely.com');
