-- Índices que sustentam a ordenação paginada no banco.
--
-- Criados depois de medir, não antes. Com 50 mil vagas ativas, os planos reais foram:
--
--   sort=date    ORDER BY COALESCE(published_at, first_seen_at) DESC LIMIT 20
--                antes: Seq Scan + top-N heapsort ............... 13,68 ms
--                depois: Index Scan ............................. 0,04 ms
--
--   sort=company ORDER BY lower(company.name) LIMIT 20
--                antes: Seq Scan + join + top-N heapsort ....... 31,54 ms
--                depois: Nested Loop sobre o índice ............ 0,22 ms
--
-- Sem estes índices a ordenação no banco seria MAIS LENTA que o pipeline atual, que carrega
-- candidatos e ordena em memória. Por isso eles vêm antes da mudança de paginação, e não
-- junto: a medição é que autoriza a mudança.

-- A ordenação por data usa COALESCE porque nem toda vaga traz data de publicação; quando
-- falta, vale quando a vimos pela primeira vez. Índice sobre a mesma expressão da consulta —
-- índice sobre published_at sozinho não serve, o planejador não reconheceria a expressão.
--
-- Parcial em 'active' e com id como desempate: o id entra na ordenação para a paginação ser
-- estável — sem critério final determinístico, duas vagas com a mesma data podem trocar de
-- lugar entre a página 1 e a página 2 e uma delas some da listagem.
CREATE INDEX IF NOT EXISTS idx_job_active_effective_date
    ON job (COALESCE(published_at, first_seen_at) DESC, id DESC)
    WHERE active;

-- Ordenar por empresa é ordenar por coluna da outra tabela. Com este índice o planejador
-- percorre as empresas já em ordem e busca as vagas de cada uma por idx_job_company, em vez
-- de ler as 50 mil vagas para ordenar 20.
CREATE INDEX IF NOT EXISTS idx_company_lower_name ON company (lower(name), id);
