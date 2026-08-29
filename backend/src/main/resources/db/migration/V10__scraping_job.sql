-- Estado persistente de uma execução de coleta.
--
-- Antes desta migration o trabalho de scraping existia apenas como Runnable dentro de um
-- ThreadPoolExecutor do SearchRefreshService e como chamada inline no JobRefreshScheduler.
-- Consequências práticas: reinício do processo perdia o trabalho em andamento sem deixar
-- rastro, falha de fonte não gerava nova tentativa, e não havia como responder "o que está
-- coletando agora?" nem "aquela coleta que pedi terminou?". O estado precisa morar onde
-- todas as instâncias enxergam e onde ele sobrevive ao processo.
--
-- Postgres e não fila dedicada: o volume é de dezenas de execuções por hora, cada uma
-- durando segundos a minutos. Nesse regime, o custo de um poll a cada poucos segundos é
-- irrelevante perto de operar um broker, e o banco já é a fonte de verdade do claim de
-- refresh (search_cache_entry) e do limite de uso (rate_limit_bucket).

CREATE TABLE IF NOT EXISTS scraping_job (
    -- UUID e não sequence: o id sai na resposta do POST e vira URL pública
    -- (GET /api/scraping/{id}). Sequence deixaria adivinhar o id do vizinho e revelaria o
    -- volume de coletas do sistema.
    id                   UUID PRIMARY KEY,
    -- Mesmo fingerprint do cache-first: JobSearchFilter.fingerprint(), SHA-256 hex.
    fingerprint          VARCHAR(64)  NOT NULL,
    -- SEARCH (feed filtrado) ou HARVEST (varredura profunda). Separado do fingerprint
    -- porque as duas operações sobre o filtro vazio são trabalhos diferentes, com
    -- orçamentos diferentes, e uma não substitui a outra.
    mode                 VARCHAR(16)  NOT NULL,
    -- Filtro serializado para o worker reconstruir o JobSearchFilter. O fingerprint é
    -- hash: não dá para voltar dele. Só critérios de busca entram aqui — nenhum dado
    -- pessoal, token ou credencial.
    filter_json          TEXT         NOT NULL,
    -- Texto enviado às fontes, mantido para diagnóstico legível no banco.
    query_text           VARCHAR(500),
    -- QUEUED | RUNNING | COMPLETED | FAILED.
    status               VARCHAR(20)  NOT NULL,
    -- Orçamento de tempo da execução, decidido por quem enfileira (busca sob demanda,
    -- refresh programado e varredura profunda têm ordens de grandeza diferentes).
    budget_seconds       INTEGER      NOT NULL,
    -- NULL = trabalho interno (scheduler). Não nulo = pedido manual, e só esse usuário
    -- consulta a linha.
    requested_by_user_id BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Início da tentativa corrente, regravado a cada claim.
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    -- Antes disto o worker não pega a linha. É o que implementa o backoff.
    next_attempt_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Incrementado no claim: conta tentativas iniciadas, não falhas.
    attempt_count        INTEGER      NOT NULL DEFAULT 0,
    max_attempts         INTEGER      NOT NULL,
    -- Mensagem curta e já truncada. Stack trace fica no log, não na tabela.
    last_error           VARCHAR(500),
    worker_id            VARCHAR(120),
    -- Até quando o claim vale. Passado isso, a linha é recuperável por qualquer worker.
    lease_until          TIMESTAMPTZ,
    CONSTRAINT ck_scraping_job_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_scraping_job_mode
        CHECK (mode IN ('SEARCH', 'HARVEST'))
);

-- Idempotência. UNIQUE(fingerprint) puro seria errado: impediria coletar o mesmo filtro
-- amanhã. O que não pode existir é mais de uma execução ATIVA do mesmo trabalho — é o
-- índice parcial que expressa isso, e é ele o árbitro do ON CONFLICT no enqueue.
-- Terminado (COMPLETED/FAILED) sai do índice, então o histórico cresce sem conflito e a
-- mesma combinação pode ser coletada de novo quando envelhecer.
CREATE UNIQUE INDEX IF NOT EXISTS uq_scraping_job_active
    ON scraping_job (fingerprint, mode)
    WHERE status IN ('QUEUED', 'RUNNING');

-- Pickup do worker: WHERE status = 'QUEUED' AND next_attempt_at <= now ORDER BY
-- next_attempt_at. Sem isto, cada poll varreria o histórico inteiro.
CREATE INDEX IF NOT EXISTS idx_scraping_job_pickup
    ON scraping_job (next_attempt_at)
    WHERE status = 'QUEUED';

-- Recuperação de lease vencida.
CREATE INDEX IF NOT EXISTS idx_scraping_job_lease
    ON scraping_job (lease_until)
    WHERE status = 'RUNNING';

-- Consulta do usuário e limpeza do histórico.
CREATE INDEX IF NOT EXISTS idx_scraping_job_user ON scraping_job (requested_by_user_id);
CREATE INDEX IF NOT EXISTS idx_scraping_job_created ON scraping_job (created_at);
