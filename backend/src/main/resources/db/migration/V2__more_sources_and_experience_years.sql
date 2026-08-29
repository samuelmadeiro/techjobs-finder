-- Anos de experiência inferidos do texto da vaga (nulo quando o anúncio não informa).
ALTER TABLE job ADD COLUMN experience_years INTEGER;

-- Novas fontes com acesso automatizado permitido.
INSERT INTO job_source (code, name, base_url) VALUES
    ('jobicy',    'Jobicy',    'https://jobicy.com'),
    ('himalayas', 'Himalayas', 'https://himalayas.app')
ON CONFLICT (code) DO NOTHING;

-- A varredura profunda gera muito mais linhas: índices que sustentam os filtros mais usados.
CREATE INDEX IF NOT EXISTS idx_job_active_level_model ON job (active, experience_level, work_model);
CREATE INDEX IF NOT EXISTS idx_job_country ON job (country);
CREATE INDEX IF NOT EXISTS idx_job_normalized_title ON job (normalized_title);
