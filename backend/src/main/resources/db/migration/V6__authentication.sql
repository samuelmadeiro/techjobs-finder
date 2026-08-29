-- Autenticação de verdade: credenciais opcionais no usuário e sessões com prazo.
--
-- Até aqui a identidade era um token permanente e imutável em app_user.access_token,
-- guardado pelo navegador para sempre: sem expiração, sem revogação, sem como encerrar
-- em um dispositivo perdido. Este é o passo que separa "quem é" (app_user) de "esta
-- sessão continua valendo" (user_session).

-- ---------------------------------------------------------------------------
-- Credenciais. Nulas para quem começou anônimo: a aplicação continua utilizável sem
-- cadastro, e o usuário decide quando vincular e-mail e senha à conta que já tem.
-- ---------------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100);

-- Unicidade sobre lower(email): "Ana@x.com" e "ana@x.com" são a mesma pessoa. Parcial
-- porque a maioria das linhas não tem e-mail, e várias linhas nulas não colidem.
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_user_email
    ON app_user (lower(email)) WHERE email IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Sessões.
--
-- token_hash e não o token: um dump do banco, um backup vazado ou um log de query não
-- entregam credencial utilizável. O servidor recebe o token do cookie, calcula o hash e
-- procura por ele — a coluna é única e indexada, então é uma busca por índice.
--
-- SHA-256 sem salt de propósito: o token tem 256 bits de entropia gerados pelo servidor,
-- não é senha escolhida por gente. Não há dicionário a atacar, e um hash lento aqui seria
-- pago em toda requisição autenticada.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_session (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Alimenta a expiração por inatividade sem exigir uma escrita por requisição:
    -- a aplicação só atualiza quando o valor já está velho o bastante para importar.
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Prazo absoluto: mesmo em uso contínuo, a sessão termina e precisa ser refeita.
    expires_at   TIMESTAMPTZ NOT NULL,
    -- Preenchido no logout e na revogação. Sessão revogada continua na tabela até a
    -- limpeza para que o encerramento seja auditável.
    revoked_at   TIMESTAMPTZ,
    user_agent   VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_user_session_user ON user_session (user_id);
-- A limpeza diária varre por prazo; sem este índice ela varre a tabela inteira.
CREATE INDEX IF NOT EXISTS idx_user_session_expires ON user_session (expires_at);

-- ---------------------------------------------------------------------------
-- access_token continua existindo e continua funcionando: é o que os navegadores dos
-- usuários atuais têm guardado. Vira credencial de migração — na primeira requisição
-- que chega com ele, o servidor abre uma sessão de verdade e passa a usar o cookie.
-- A coluna sai em uma migration futura, depois que a janela de transição fechar.
-- ---------------------------------------------------------------------------
-- Deixa de ser obrigatório: contas novas nascem sem ele, e a conta que migra o descarta
-- assim que ganha uma sessão — senão sobraria um token permanente válido em paralelo a uma
-- sessão que expira, que é exatamente o que esta migration veio eliminar.
-- A unicidade continua valendo; no Postgres, vários NULL não colidem entre si.
ALTER TABLE app_user ALTER COLUMN access_token DROP NOT NULL;

COMMENT ON COLUMN app_user.access_token IS
    'Legado: credencial de migração para sessões. Não emitir novos valores.';
