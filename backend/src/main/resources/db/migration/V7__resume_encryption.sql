-- Criptografia do currículo em repouso.
--
-- O que muda: file_data passa a guardar [nonce de 12 bytes || ciphertext + tag], e o texto
-- extraído ganha uma coluna binária própria pelo mesmo motivo — texto cifrado não é texto.
-- encryption_key_id diz com qual chave o registro foi gravado, o que torna a rotação
-- possível sem parar a aplicação e permite responder "quantos registros ainda usam a chave
-- antiga?" com uma consulta.

ALTER TABLE resume_content ADD COLUMN IF NOT EXISTS encryption_key_id VARCHAR(32);
ALTER TABLE resume_content ADD COLUMN IF NOT EXISTS extracted_text_enc BYTEA;

-- Linhas antigas ficam com encryption_key_id nulo e continuam legíveis pela aplicação
-- enquanto a recifragem em segundo plano não passa por elas. É o que permite implantar
-- esta versão sem indisponibilidade e sem perder currículo nenhum.
--
-- extracted_text (TEXT, em claro) deixa de ser escrita nesta versão. A coluna só será
-- removida depois que a recifragem terminar em todos os ambientes: derrubá-la agora seria
-- destrutivo e apagaria o texto de quem ainda não foi convertido.
COMMENT ON COLUMN resume_content.extracted_text IS
    'Legado em claro. Não escrever. Removida após a recifragem completa (ver ResumeReencryptionJob).';
COMMENT ON COLUMN resume_content.file_data IS
    'Cifrado com AES-256-GCM quando encryption_key_id != NULL; em claro nas linhas legadas.';

-- Índice parcial: a recifragem procura exatamente as linhas que faltam, e ele some do
-- planejamento assim que não sobrar nenhuma.
CREATE INDEX IF NOT EXISTS idx_resume_content_pending_encryption
    ON resume_content (resume_id) WHERE encryption_key_id IS NULL;
