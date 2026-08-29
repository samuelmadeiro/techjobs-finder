-- Índices para a busca por palavra-chave.
--
-- A consulta gerada pelo JobSpecifications é, literalmente:
--
--   select j1_0.id from job j1_0
--    where j1_0.active
--      and (j1_0.normalized_title like ? escape '' or lower(j1_0.summary) like ? escape '')
--    fetch first ? rows only
--
-- e o padrão sempre começa com '%' (busca por trecho, não por prefixo). Índice B-tree não
-- serve para isso: sem prefixo conhecido não há faixa a percorrer, e o planejador cai em
-- varredura sequencial da tabela inteira a cada busca por texto. Trigramas resolvem
-- exatamente esse caso.
--
-- ---------------------------------------------------------------------------
-- Por que condicional
--
-- pg_trgm é uma extensão. Em PostgreSQL gerenciado com privilégio restrito, CREATE
-- EXTENSION pode ser recusado — e uma migration que falha impede a aplicação inteira de
-- subir por causa de uma otimização. A busca não depende do índice para funcionar: sem ele
-- o LIKE continua correto, apenas mais lento. Perder desempenho é aceitável; não subir, não.
--
-- O bloco abaixo trata exatamente dois erros, os que significam "este servidor não me
-- deixa": insufficient_privilege e feature_not_supported. Qualquer outro erro continua
-- subindo e quebrando a migration — esconder falha de banco indiscriminadamente
-- transformaria um problema real em mistério silencioso.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    trigram_ready BOOLEAN := FALSE;
BEGIN
    -- Já instalada? Então não há nada a pedir ao servidor.
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        trigram_ready := TRUE;
    ELSIF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_trgm') THEN
        BEGIN
            CREATE EXTENSION pg_trgm;
            trigram_ready := TRUE;
        EXCEPTION
            WHEN insufficient_privilege OR feature_not_supported THEN
                RAISE WARNING
                    'pg_trgm existe neste servidor mas o usuário atual não pode instalá-la (%). '
                    'A busca por palavra-chave continua funcionando sem índice de trigrama, '
                    'com varredura sequencial. Peça a um administrador: CREATE EXTENSION pg_trgm;',
                    SQLERRM;
        END;
    ELSE
        RAISE WARNING
            'pg_trgm não está disponível neste servidor. A busca por palavra-chave continua '
            'funcionando, sem índice de trigrama e portanto mais lenta em bases grandes.';
    END IF;

    IF trigram_ready THEN
        -- Parciais em 'active': toda busca filtra vaga ativa, então indexar as inativas só
        -- aumentaria o índice sem nunca ser consultado.
        --
        -- O segundo índice é sobre a expressão lower(summary), e não sobre a coluna: o
        -- índice só entra se casar com a expressão exata que aparece na consulta.
        CREATE INDEX IF NOT EXISTS idx_job_title_trgm
            ON job USING gin (normalized_title gin_trgm_ops)
            WHERE active;

        CREATE INDEX IF NOT EXISTS idx_job_summary_trgm
            ON job USING gin (lower(summary) gin_trgm_ops)
            WHERE active;

        RAISE NOTICE 'pg_trgm ativa: índices de trigrama criados para a busca por texto.';
    END IF;
END
$$;

-- Removido independentemente da extensão: era o único índice sobre normalized_title, e a
-- única consulta que toca a coluna é o LIKE '%...%', que ele não consegue atender nem com
-- trigrama disponível nem sem. Mantê-lo custaria escrita em toda ingestão de vaga sem
-- jamais ser lido.
DROP INDEX IF EXISTS idx_job_normalized_title;
