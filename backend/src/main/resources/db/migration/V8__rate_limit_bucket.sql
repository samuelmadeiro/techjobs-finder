-- Contador de uso compartilhado entre instâncias.
--
-- O limitador anterior vivia na memória de cada JVM: com três réplicas atrás do balanceador,
-- o teto efetivo virava o triplo do configurado, e reiniciar um contêiner zerava o limite de
-- quem estava abusando. O estado precisa morar onde todas as instâncias enxergam.
--
-- Postgres e não Redis: é uma escrita por upload — a rota mais cara do sistema, que já grava
-- arquivo e roda extração de texto —, então o custo relativo é irrelevante. Trazer um segundo
-- armazenamento para guardar um contador acrescentaria um ponto de falha e uma dependência
-- operacional que este volume não justifica. Se um dia o limite passar a valer para rotas de
-- leitura de alto tráfego, a conta muda e o Redis passa a fazer sentido.

CREATE TABLE IF NOT EXISTS rate_limit_bucket (
    -- "user:{id}" para autenticado, "ip:{endereço}" para anônimo. Quem monta a chave é a
    -- aplicação; aqui ela é só o identificador do balde.
    bucket_key VARCHAR(200) PRIMARY KEY,
    -- Fichas restantes. Fracionário porque a reposição é contínua no tempo, não em degraus.
    tokens     DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

-- A limpeza diária remove baldes parados; sem o índice ela varreria a tabela inteira.
CREATE INDEX IF NOT EXISTS idx_rate_limit_bucket_updated ON rate_limit_bucket (updated_at);
