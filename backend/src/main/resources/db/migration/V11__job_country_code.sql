-- País da vaga como código estável, não como texto livre.
--
-- A coluna `country` que já existia guarda o que a fonte mandou: "Brazil", "Brasil",
-- "Anywhere", "Remote", "United States", "USA", às vezes uma cidade, às vezes vazio. Dá para
-- exibir, não dá para filtrar: `country ILIKE '%brasil%'` erra "Brazil" e casa "Brasilia" por
-- acidente, e o índice de texto não ajuda em nada disso. O filtro por país precisa de um
-- valor fechado, decidido uma vez na ingestão.
--
-- ZZ (reservado pela ISO-3166 para uso próprio) é o balde "sem país definido": vaga remota
-- global, região multipaís ("LATAM", "Europe") ou localização não atribuível. Vaga em ZZ
-- aparece na busca de qualquer país, porque é isso que ela é — aberta a qualquer lugar.
-- Quem não é reconhecido cai aqui: o classificador erra para o lado de mostrar a mais, nunca
-- para o de esconder a vaga do país certo.

-- VARCHAR e não CHAR: CHAR completa com espaços à direita, e comparar 'BR' com
-- 'BR' padded é fonte de bug silencioso em filtro.
ALTER TABLE job ADD COLUMN IF NOT EXISTS country_code VARCHAR(2);

-- Retroativo para o acervo já coletado. É a mesma tabela do CountryCatalog, escrita em SQL:
-- as duas precisam concordar, e a diferença é que esta roda uma vez e aquela roda em toda
-- ingestão. Nome de país primeiro, cidade depois — a ordem do classificador.
UPDATE job SET country_code = CASE
    WHEN location ILIKE '%brasil%' OR location ILIKE '%brazil%'
      OR country  ILIKE '%brasil%' OR country  ILIKE '%brazil%' THEN 'BR'
    WHEN location ILIKE '%united states%' OR location ILIKE '%estados unidos%'
      OR location ILIKE '%usa%' OR country ILIKE '%united states%' OR country ILIKE '%usa%'
        THEN 'US'
    WHEN location ILIKE '%canada%' OR country ILIKE '%canada%' THEN 'CA'
    WHEN location ILIKE '%portugal%' OR country ILIKE '%portugal%' THEN 'PT'
    WHEN location ILIKE '%united kingdom%' OR location ILIKE '%reino unido%'
      OR location ILIKE '%england%' OR location ILIKE '%scotland%' OR location ILIKE '%wales%'
      OR country ILIKE '%united kingdom%' OR country ILIKE '%uk' THEN 'GB'
    WHEN location ILIKE '%germany%' OR location ILIKE '%deutschland%'
      OR location ILIKE '%alemanha%' OR country ILIKE '%germany%' THEN 'DE'
    WHEN location ILIKE '%spain%' OR location ILIKE '%espa%a%' OR country ILIKE '%spain%'
        THEN 'ES'
    WHEN location ILIKE '%france%' OR location ILIKE '%fran%a%' OR country ILIKE '%france%'
        THEN 'FR'
    WHEN location ILIKE '%australia%' OR country ILIKE '%australia%' THEN 'AU'
    -- Cidades, para as fontes que mandam só elas ("London", "Berlin", "Toronto").
    WHEN location ILIKE '%london%' OR location ILIKE '%manchester%'
      OR location ILIKE '%edinburgh%' OR location ILIKE '%birmingham%'
      OR location ILIKE '%bristol%' OR location ILIKE '%glasgow%' THEN 'GB'
    WHEN location ILIKE '%berlin%' OR location ILIKE '%munich%' OR location ILIKE '%m_nchen%'
      OR location ILIKE '%hamburg%' OR location ILIKE '%frankfurt%'
      OR location ILIKE '%stuttgart%' OR location ILIKE '%leipzig%' THEN 'DE'
    WHEN location ILIKE '%sao paulo%' OR location ILIKE '%s_o paulo%'
      OR location ILIKE '%rio de janeiro%' OR location ILIKE '%belo horizonte%'
      OR location ILIKE '%curitiba%' OR location ILIKE '%porto alegre%' THEN 'BR'
    WHEN location ILIKE '%new york%' OR location ILIKE '%san francisco%'
      OR location ILIKE '%seattle%' OR location ILIKE '%austin%' OR location ILIKE '%boston%'
      OR location ILIKE '%chicago%' OR location ILIKE '%los angeles%' THEN 'US'
    WHEN location ILIKE '%toronto%' OR location ILIKE '%vancouver%'
      OR location ILIKE '%montreal%' OR location ILIKE '%calgary%' THEN 'CA'
    WHEN location ILIKE '%lisboa%' OR location ILIKE '%lisbon%' OR location ILIKE '%porto%'
        THEN 'PT'
    WHEN location ILIKE '%madrid%' OR location ILIKE '%barcelona%'
      OR location ILIKE '%valencia%' THEN 'ES'
    WHEN location ILIKE '%paris%' OR location ILIKE '%lyon%' OR location ILIKE '%marseille%'
        THEN 'FR'
    WHEN location ILIKE '%sydney%' OR location ILIKE '%melbourne%'
      OR location ILIKE '%brisbane%' OR location ILIKE '%perth%' THEN 'AU'
    ELSE 'ZZ'
END
WHERE country_code IS NULL;

ALTER TABLE job ALTER COLUMN country_code SET DEFAULT 'ZZ';
ALTER TABLE job ALTER COLUMN country_code SET NOT NULL;

-- O filtro é sempre `country_code IN (:code, 'ZZ')` combinado com `active = true`. Índice
-- composto porque a busca nunca pergunta por país sem perguntar por vaga ativa.
CREATE INDEX IF NOT EXISTS idx_job_country_code_active ON job (country_code, active);
