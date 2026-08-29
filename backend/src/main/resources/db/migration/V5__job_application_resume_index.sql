-- Índice de apoio à única chave estrangeira que estava sem um.
--
-- job_application.resume_id é declarada ON DELETE SET NULL. Quando a retenção apaga um
-- currículo, o Postgres precisa encontrar as candidaturas que apontavam para ele antes de
-- zerar a coluna — e, sem índice, essa checagem é uma varredura da tabela inteira por
-- currículo removido. A purga é em lote, então o custo se multiplica pelo tamanho do lote.
--
-- As outras duas colunas de chave estrangeira já tinham índice desde a V3
-- (idx_job_application_user e idx_job_application_job); esta ficou de fora.
CREATE INDEX IF NOT EXISTS idx_job_application_resume ON job_application (resume_id);
