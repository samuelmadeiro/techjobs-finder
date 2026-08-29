-- Etapa 3/4: descrição completa da vaga, salário estruturado, currículo e matching.

-- ---------------------------------------------------------------------------
-- job: a listagem já tinha só o resumo truncado. A tela de detalhes precisa do
-- texto integral, e o matching precisa do salário em números, não em string.
-- ---------------------------------------------------------------------------
ALTER TABLE job ADD COLUMN IF NOT EXISTS description       TEXT;
ALTER TABLE job ADD COLUMN IF NOT EXISTS short_description VARCHAR(400);
ALTER TABLE job ADD COLUMN IF NOT EXISTS salary_min        NUMERIC(12, 2);
ALTER TABLE job ADD COLUMN IF NOT EXISTS salary_max        NUMERIC(12, 2);
ALTER TABLE job ADD COLUMN IF NOT EXISTS salary_currency   VARCHAR(3);
ALTER TABLE job ADD COLUMN IF NOT EXISTS salary_period     VARCHAR(10);
ALTER TABLE job ADD COLUMN IF NOT EXISTS expiration_date   TIMESTAMPTZ;
ALTER TABLE job ADD COLUMN IF NOT EXISTS created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE job ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Linhas de "Requisitos" e "Diferenciais" extraídas da descrição.
-- Tabela própria em vez de texto concatenado: a ordem importa e o matching
-- consulta os itens individualmente.
CREATE TABLE IF NOT EXISTS job_requirement (
    id       BIGSERIAL PRIMARY KEY,
    job_id   BIGINT      NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    kind     VARCHAR(20) NOT NULL,
    position INTEGER     NOT NULL DEFAULT 0,
    text     VARCHAR(500) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_job_requirement_job ON job_requirement (job_id);

-- ---------------------------------------------------------------------------
-- company
-- ---------------------------------------------------------------------------
ALTER TABLE company ADD COLUMN IF NOT EXISTS description VARCHAR(2000);
ALTER TABLE company ADD COLUMN IF NOT EXISTS logo_url    VARCHAR(1000);
ALTER TABLE company ADD COLUMN IF NOT EXISTS updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- ---------------------------------------------------------------------------
-- Usuário. Sem senha: a aplicação ainda não tem autenticação, e guardar hash de
-- credencial sem um fluxo de login completo só criaria superfície de ataque.
-- A identidade é um token opaco gerado pelo servidor, guardado pelo navegador.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
    id           BIGSERIAL PRIMARY KEY,
    access_token VARCHAR(64)  NOT NULL UNIQUE,
    display_name VARCHAR(160),
    email        VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- Currículo. Só os metadados e o perfil extraído ficam aqui; o arquivo e o texto
-- integral moram em resume_content, que é lido apenas quando alguém pede.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS resume (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(120) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    checksum          VARCHAR(64)  NOT NULL,
    candidate_name    VARCHAR(160),
    headline          VARCHAR(500),
    experience_level  VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    experience_years  INTEGER,
    preferred_work_model VARCHAR(20),
    location          VARCHAR(255),
    parse_status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    parse_message     VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_resume_user ON resume (user_id);

-- Dados pesados e sensíveis do currículo, separados para nunca serem carregados por
-- engano junto do perfil. BYTEA e não arquivo em disco: não existe caminho interno
-- para vazar, nada é servido estaticamente e o DELETE apaga o binário de fato.
CREATE TABLE IF NOT EXISTS resume_content (
    resume_id      BIGINT PRIMARY KEY REFERENCES resume (id) ON DELETE CASCADE,
    file_data      BYTEA NOT NULL,
    extracted_text TEXT
);

-- Skill reconhecida no currículo. technology_id nulo = termo que o catálogo ainda
-- não conhece; guardamos assim mesmo para exibir ao usuário e evoluir a taxonomia.
CREATE TABLE IF NOT EXISTS resume_skill (
    id            BIGSERIAL PRIMARY KEY,
    resume_id     BIGINT       NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    technology_id BIGINT       REFERENCES technology (id),
    label         VARCHAR(120) NOT NULL,
    occurrences   INTEGER      NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_resume_skill_resume ON resume_skill (resume_id);
CREATE INDEX IF NOT EXISTS idx_resume_skill_tech ON resume_skill (technology_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_resume_skill_label ON resume_skill (resume_id, label);

-- Formação, certificações, projetos e experiências: mesma forma, kind diferente.
CREATE TABLE IF NOT EXISTS resume_item (
    id        BIGSERIAL PRIMARY KEY,
    resume_id BIGINT       NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    kind      VARCHAR(20)  NOT NULL,
    position  INTEGER      NOT NULL DEFAULT 0,
    text      VARCHAR(500) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_resume_item_resume ON resume_item (resume_id);

-- ---------------------------------------------------------------------------
-- Candidatura registrada pelo usuário (histórico de "acessei/apliquei").
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_application (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id     BIGINT      NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    resume_id  BIGINT      REFERENCES resume (id) ON DELETE SET NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'SAVED',
    score      INTEGER,
    notes      VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_job_application UNIQUE (user_id, job_id)
);

CREATE INDEX IF NOT EXISTS idx_job_application_user ON job_application (user_id);
CREATE INDEX IF NOT EXISTS idx_job_application_job ON job_application (job_id);
