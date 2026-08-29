package com.techjobs.finder.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techjobs.finder.config.EncryptionProperties;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garantias da cifra do currículo, sem banco e sem Spring: é aritmética de bytes.
 *
 * <p>O que precisa ser verdade aqui é o que nenhum teste de integração consegue afirmar
 * sozinho — nonce nunca repetido, dado adulterado que não passa, chave errada que não abre.
 */
class ResumeCipherTest {

    private static final byte[] CONTENT =
            "Fulana de Tal - Desenvolvedora Java - fulana@exemplo.com".getBytes(StandardCharsets.UTF_8);

    private static EncryptionProperties properties(String activeKeyId, Map<String, String> keys) {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setKeys(new LinkedHashMap<>(keys));
        properties.setActiveKeyId(activeKeyId);
        properties.validate();
        return properties;
    }

    private static String key(byte seed) {
        byte[] material = new byte[32];
        Arrays.fill(material, seed);
        return Base64.getEncoder().encodeToString(material);
    }

    private static ResumeCipher cipherWith(String activeKeyId, Map<String, String> keys) {
        return new ResumeCipher(properties(activeKeyId, keys));
    }

    private static ResumeCipher defaultCipher() {
        return cipherWith("v1", Map.of("v1", key((byte) 1)));
    }

    @Test
    @DisplayName("o que sai da cifra volta idêntico")
    void roundTripPreservesContent() {
        ResumeCipher cipher = defaultCipher();

        ResumeCipher.Encrypted encrypted = cipher.encrypt(CONTENT);

        assertThat(encrypted.keyId()).isEqualTo("v1");
        assertThat(cipher.decrypt(encrypted.keyId(), encrypted.payload())).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("o texto original não aparece no que é gravado")
    void ciphertextDoesNotContainPlaintext() {
        ResumeCipher.Encrypted encrypted = defaultCipher().encrypt(CONTENT);

        String asLatin1 = new String(encrypted.payload(), StandardCharsets.ISO_8859_1);
        assertThat(asLatin1).doesNotContain("Fulana").doesNotContain("fulana@exemplo.com");
        // Nonce e tag acrescentam 28 bytes; o corpo cifrado tem o tamanho do original.
        assertThat(encrypted.payload()).hasSize(CONTENT.length + 12 + 16);
    }

    @Test
    @DisplayName("cada operação usa um nonce novo — cifrar o mesmo dado dá saídas diferentes")
    void neverReusesNonce() {
        ResumeCipher cipher = defaultCipher();
        Set<String> nonces = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            byte[] payload = cipher.encrypt(CONTENT).payload();
            nonces.add(Base64.getEncoder().encodeToString(Arrays.copyOfRange(payload, 0, 12)));
        }

        // Nonce repetido com a mesma chave em GCM permite forjar dados; 500 amostras sem
        // colisão é o mínimo que se espera de 96 bits aleatórios.
        assertThat(nonces).hasSize(500);
    }

    @Test
    @DisplayName("um byte alterado no banco não vira texto diferente: vira erro")
    void detectsTamperedContent() {
        ResumeCipher cipher = defaultCipher();
        ResumeCipher.Encrypted encrypted = cipher.encrypt(CONTENT);

        byte[] adulterado = encrypted.payload().clone();
        adulterado[adulterado.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(encrypted.keyId(), adulterado))
                .isInstanceOf(ResumeDecryptionException.class);
    }

    @Test
    @DisplayName("chave errada não decifra")
    void wrongKeyFails() {
        ResumeCipher.Encrypted encrypted = defaultCipher().encrypt(CONTENT);
        ResumeCipher outra = cipherWith("v1", Map.of("v1", key((byte) 9)));

        assertThatThrownBy(() -> outra.decrypt(encrypted.keyId(), encrypted.payload()))
                .isInstanceOf(ResumeDecryptionException.class);
    }

    @Test
    @DisplayName("chave desconhecida é recusada com mensagem clara")
    void unknownKeyIdFails() {
        ResumeCipher cipher = defaultCipher();
        ResumeCipher.Encrypted encrypted = cipher.encrypt(CONTENT);

        assertThatThrownBy(() -> cipher.decrypt("v99", encrypted.payload()))
                .isInstanceOf(ResumeDecryptionException.class)
                .hasMessageContaining("v99");
    }

    @Test
    @DisplayName("conteúdo truncado não passa por decifragem")
    void truncatedContentFails() {
        ResumeCipher cipher = defaultCipher();

        assertThatThrownBy(() -> cipher.decrypt("v1", new byte[]{1, 2, 3}))
                .isInstanceOf(ResumeDecryptionException.class);
    }

    @Test
    @DisplayName("rotação: a chave nova cifra, a antiga continua abrindo o acervo")
    void supportsKeyRotation() {
        Map<String, String> duas = new LinkedHashMap<>();
        duas.put("v1", key((byte) 1));
        duas.put("v2", key((byte) 2));

        ResumeCipher antes = cipherWith("v1", Map.of("v1", key((byte) 1)));
        ResumeCipher.Encrypted gravadoComV1 = antes.encrypt(CONTENT);

        ResumeCipher depois = cipherWith("v2", duas);

        assertThat(depois.encrypt(CONTENT).keyId()).isEqualTo("v2");
        assertThat(depois.decrypt("v1", gravadoComV1.payload())).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("sem chave configurada, a aplicação não sobe")
    void refusesToStartWithoutKeys() {
        EncryptionProperties vazio = new EncryptionProperties();

        // Falhar alto na subida é deliberado: o contrário seria gravar currículo em claro
        // porque alguém esqueceu uma variável de ambiente, sem ninguém perceber.
        assertThatThrownBy(vazio::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nenhuma chave de criptografia configurada");
    }

    @Test
    @DisplayName("chave com tamanho errado é recusada na subida")
    void refusesShortKey() {
        EncryptionProperties curta = new EncryptionProperties();
        curta.setKeys(Map.of("v1", Base64.getEncoder().encodeToString(new byte[16])));
        curta.setActiveKeyId("v1");

        assertThatThrownBy(curta::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256 exige");
    }

    @Test
    @DisplayName("chave ativa que não existe é recusada na subida")
    void refusesUnknownActiveKey() {
        EncryptionProperties incoerente = new EncryptionProperties();
        incoerente.setKeys(Map.of("v1", key((byte) 1)));
        incoerente.setActiveKeyId("v7");

        assertThatThrownBy(incoerente::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-key-id");
    }
}
