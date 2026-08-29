package com.techjobs.finder.config;

import jakarta.annotation.PostConstruct;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chaves de criptografia do currículo.
 *
 * <p>Vêm do ambiente, nunca do banco nem do repositório: quem tem um dump do Postgres não
 * pode ter também a chave, senão a criptografia em repouso não protege de nada. Em
 * produção, o valor deve chegar por secret manager ou variável de ambiente injetada pelo
 * orquestrador.
 *
 * <p>Várias chaves podem coexistir, indexadas por identificador:
 *
 * <pre>
 *   techjobs.encryption.keys.v1 = &lt;base64 de 32 bytes&gt;
 *   techjobs.encryption.keys.v2 = &lt;base64 de 32 bytes&gt;
 *   techjobs.encryption.active-key-id = v2
 * </pre>
 *
 * <p>É o suficiente para rotacionar sem parar a aplicação: sobe-se a chave nova como ativa,
 * a antiga fica configurada para continuar decifrando o que já existe, e a rotina de
 * recifragem converte o acervo em segundo plano. Não há KMS aqui de propósito — este
 * projeto ainda não tem o problema que um KMS resolve.
 */
@ConfigurationProperties(prefix = "techjobs.encryption")
public class EncryptionProperties {

    /** AES-256: 32 bytes. Recusar tamanho diferente evita chave fraca por engano. */
    private static final int KEY_BYTES = 32;

    private Map<String, String> keys = new LinkedHashMap<>();

    private String activeKeyId;

    private final Map<String, SecretKey> parsed = new LinkedHashMap<>();

    /**
     * Valida na subida, não no primeiro upload.
     *
     * <p>Sem chave, a aplicação não sobe. A alternativa — gravar currículo em claro quando
     * a configuração falta — transformaria um esquecimento de deploy em vazamento
     * silencioso, e ninguém descobriria até ser tarde.
     */
    @PostConstruct
    public void validate() {
        if (keys.isEmpty()) {
            throw new IllegalStateException("""
                    Nenhuma chave de criptografia configurada (techjobs.encryption.keys.*).
                    O currículo é dado pessoal e não é gravado sem cifra. Gere uma chave com
                    'openssl rand -base64 32' e informe-a pelo ambiente.""");
        }
        keys.forEach((id, value) -> parsed.put(id, toKey(id, value)));

        if (activeKeyId == null || !parsed.containsKey(activeKeyId)) {
            throw new IllegalStateException(
                    "techjobs.encryption.active-key-id deve apontar para uma chave configurada; "
                            + "conhecidas: " + parsed.keySet());
        }
    }

    @Contract("_, _ -> new")
    private @NonNull SecretKey toKey(String id, @NonNull String base64) {
        byte[] material;
        try {
            material = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Chave '%s' não está em Base64 válido.".formatted(id), e);
        }
        if (material.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "Chave '%s' tem %d bytes; AES-256 exige %d.".formatted(id, material.length, KEY_BYTES));
        }
        return new SecretKeySpec(material, "AES");
    }

    public SecretKey key(String keyId) {
        SecretKey key = parsed.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Chave de criptografia desconhecida: " + keyId);
        }
        return key;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }
}
