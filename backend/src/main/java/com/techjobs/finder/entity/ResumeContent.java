package com.techjobs.finder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Arquivo original do currículo e o texto extraído dele, cifrados em repouso.
 *
 * <p>Entidade separada de {@link Resume} por decisão de projeto, não por normalização:
 * assim o perfil pode ser lido milhares de vezes sem que o binário de megabytes venha
 * junto, e o dado mais sensível do sistema só sai do banco quando alguém o pede
 * explicitamente. A chave primária é o próprio id do currículo.
 *
 * <p>Os dois campos de conteúdo guardam {@code [nonce || ciphertext+tag]} e são inúteis sem
 * a chave, que vive no ambiente. O texto extraído recebe o mesmo tratamento do arquivo: ele
 * carrega nome, telefone, endereço e histórico — deixar de ser PDF não o torna menos
 * pessoal.
 */
@Entity
@Table(name = "resume_content")
public class ResumeContent {

    @Id
    @Column(name = "resume_id")
    private Long resumeId;

    @Column(name = "file_data", nullable = false, columnDefinition = "bytea")
    private byte[] fileData;

    /**
     * Texto extraído em claro das linhas anteriores à criptografia. Somente leitura: nada
     * novo é gravado aqui, e a recifragem move o conteúdo para {@link #extractedTextEnc}.
     */
    @Column(name = "extracted_text", columnDefinition = "text")
    private String legacyExtractedText;

    @Column(name = "extracted_text_enc", columnDefinition = "bytea")
    private byte[] extractedTextEnc;

    /** Nulo enquanto o registro for legado em claro. */
    @Column(name = "encryption_key_id", length = 32)
    private String encryptionKeyId;

    protected ResumeContent() {
    }

    public ResumeContent(Long resumeId, byte[] fileData, byte[] extractedTextEnc,
                         String encryptionKeyId) {
        this.resumeId = resumeId;
        this.fileData = fileData;
        this.extractedTextEnc = extractedTextEnc;
        this.encryptionKeyId = encryptionKeyId;
    }

    /** Registro gravado antes desta versão, ainda em claro. */
    public boolean isEncrypted() {
        return encryptionKeyId != null;
    }

    /**
     * Substitui o conteúdo em claro pelo cifrado. Zera a coluna legada na mesma operação:
     * manter as duas cópias significaria continuar com o texto exposto.
     */
    public void replaceWithEncrypted(byte[] fileData, byte[] extractedTextEnc, String keyId) {
        this.fileData = fileData;
        this.extractedTextEnc = extractedTextEnc;
        this.encryptionKeyId = keyId;
        this.legacyExtractedText = null;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public byte[] getFileData() {
        return fileData;
    }

    public byte[] getExtractedTextEnc() {
        return extractedTextEnc;
    }

    public String getLegacyExtractedText() {
        return legacyExtractedText;
    }

    public String getEncryptionKeyId() {
        return encryptionKeyId;
    }
}
