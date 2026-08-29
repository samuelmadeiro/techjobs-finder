package com.techjobs.finder.security;

import com.techjobs.finder.config.EncryptionProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Criptografia do conteúdo do currículo, em repouso.
 *
 * <p>AES-256-GCM da própria JVM (JCE) — nada de construção caseira. GCM porque autentica
 * além de cifrar: alterar um byte no banco não produz texto diferente, produz erro. Sem
 * isso, quem tivesse escrita no banco poderia trocar o conteúdo de um currículo por outro
 * sem que a aplicação percebesse.
 *
 * <p>Formato gravado, um único blob por campo:
 *
 * <pre>
 *   [ 12 bytes de nonce ][ ciphertext + tag de 16 bytes ]
 * </pre>
 *
 * <p>Nonce junto do dado, e não em coluna separada, de propósito: são inseparáveis por
 * natureza — decifrar com o nonce de outro registro é impossível —, e colunas paralelas
 * podem dessincronizar em um backup parcial, em um UPDATE mal escrito ou em uma migração.
 * O identificador da chave, esse sim, é coluna própria: precisa ser consultável para saber
 * quantos registros ainda usam a chave antiga durante uma rotação.
 *
 * <p>Nonce novo a cada operação, de {@link SecureRandom}. Repetir nonce com a mesma chave
 * em GCM não vaza só a mensagem: permite recuperar a chave de autenticação e forjar dados.
 * São 96 bits aleatórios por registro, o que mantém a probabilidade de colisão desprezível
 * na ordem de grandeza deste sistema.
 */
public class ResumeCipher {

    /** 96 bits: tamanho recomendado para GCM e o único que dispensa derivação interna. */
    private static final int NONCE_BYTES = 12;

    /** 128 bits de tag de autenticação — o máximo do GCM. */
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EncryptionProperties properties;

    public ResumeCipher(EncryptionProperties properties) {
        this.properties = properties;
    }

    /** Dado cifrado e a chave usada, para o chamador gravar os dois juntos. */
    public record Encrypted(String keyId, byte[] payload) {
    }

    public Encrypted encrypt(byte[] plaintext) {
        if (plaintext == null) {
            return null;
        }
        String keyId = properties.getActiveKeyId();
        SecretKey key = properties.key(keyId);

        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return new Encrypted(keyId, payload);
        } catch (GeneralSecurityException e) {
            // Falhar aqui significa configuração de chave errada, não dado errado.
            throw new IllegalStateException("Não foi possível cifrar o conteúdo do currículo", e);
        }
    }

    public Encrypted encrypt(String plaintext) {
        return plaintext == null ? null : encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decifra com a chave indicada no registro.
     *
     * <p>É isso que permite rotação sem parar a aplicação: as chaves antigas continuam
     * configuradas e continuam decifrando o que foi gravado com elas, enquanto tudo o que é
     * gravado a partir de agora usa a nova.
     *
     * @throws ResumeDecryptionException chave desconhecida, dado truncado ou adulterado
     */
    public byte[] decrypt(String keyId, byte[] payload) {
        if (payload == null) {
            return null;
        }
        if (payload.length <= NONCE_BYTES) {
            throw new ResumeDecryptionException("Conteúdo cifrado menor que o próprio nonce.");
        }
        SecretKey key;
        try {
            key = properties.key(keyId);
        } catch (IllegalStateException e) {
            throw new ResumeDecryptionException(
                    "Chave '%s' não está configurada nesta instância.".formatted(keyId));
        }

        byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException cai aqui: chave errada ou bytes adulterados. A exceção
            // original não sobe para o cliente — a mensagem não diz qual dos dois foi.
            throw new ResumeDecryptionException("Conteúdo do currículo não pôde ser decifrado.");
        }
    }

    public String decryptToString(String keyId, byte[] payload) {
        byte[] plaintext = decrypt(keyId, payload);
        return plaintext == null ? null : new String(plaintext, StandardCharsets.UTF_8);
    }
}
