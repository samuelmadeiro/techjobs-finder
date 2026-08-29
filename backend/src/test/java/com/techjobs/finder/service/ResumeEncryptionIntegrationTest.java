package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.entity.ResumeContent;
import com.techjobs.finder.repository.ResumeContentRepository;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.security.ResumeCipher;
import com.techjobs.finder.service.AuthenticationService.IssuedSession;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * O que está gravado no Postgres depois de um upload real.
 *
 * <p>Este é o teste que importa para a promessa da Fase 1: não basta a cifra funcionar em
 * memória — o que precisa ser verdade é que o conteúdo dentro do banco não é legível, e que
 * a aplicação continua conseguindo recuperá-lo.
 */
class ResumeEncryptionIntegrationTest extends PostgresIntegrationTest {

    /** Um PDF mínimo com texto reconhecível, para procurá-lo (e não achá-lo) no banco. */
    private static final String MARCA = "Fulana-de-Tal-Desenvolvedora";

    private static final byte[] PDF_BYTES =
            ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n% " + MARCA
                    + "\ntrailer<</Root 1 0 R>>\n%%EOF").getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ResumeContentRepository contentRepository;

    @Autowired
    private ResumeCipher cipher;

    @Autowired
    private ResumeReencryptionJob reencryptionJob;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM app_user");
    }

    private Long upload() {
        IssuedSession session = authenticationService.openAnonymousSession("teste");
        AuthenticatedUser owner = session.user();
        var file = new MockMultipartFile("file", "curriculo.pdf", "application/pdf", PDF_BYTES);
        resumeService.upload(file, owner);
        return jdbc.queryForObject("SELECT r.id FROM resume r WHERE r.user_id = ?",
                Long.class, owner.id());
    }

    @Test
    @DisplayName("o arquivo gravado no banco não contém o conteúdo original")
    void storedFileIsNotPlaintext() {
        Long resumeId = upload();

        byte[] armazenado = jdbc.queryForObject(
                "SELECT file_data FROM resume_content WHERE resume_id = ?", byte[].class, resumeId);
        String keyId = jdbc.queryForObject(
                "SELECT encryption_key_id FROM resume_content WHERE resume_id = ?",
                String.class, resumeId);

        assertThat(keyId).isEqualTo("v1");
        String comoTexto = new String(armazenado, StandardCharsets.ISO_8859_1);
        // Nem a marca, nem sequer o cabeçalho de PDF sobrevivem em claro.
        assertThat(comoTexto).doesNotContain(MARCA).doesNotContain("%PDF");

        // E a aplicação recupera o original byte a byte.
        assertThat(cipher.decrypt(keyId, armazenado)).isEqualTo(PDF_BYTES);
    }

    @Test
    @DisplayName("o texto extraído também é cifrado, e a coluna em claro não é usada")
    void extractedTextIsEncryptedToo() {
        Long resumeId = upload();

        String legado = jdbc.queryForObject(
                "SELECT extracted_text FROM resume_content WHERE resume_id = ?",
                String.class, resumeId);

        // A coluna antiga não recebe nada: texto de currículo é tão pessoal quanto o PDF.
        assertThat(legado).isNull();

        ResumeContent content = contentRepository.findById(resumeId).orElseThrow();
        assertThat(content.isEncrypted()).isTrue();
        if (content.getExtractedTextEnc() != null) {
            String recuperado = cipher.decryptToString(content.getEncryptionKeyId(),
                    content.getExtractedTextEnc());
            assertThat(recuperado).isNotBlank();
        }
    }

    @Test
    @DisplayName("registro legado em claro é convertido pela recifragem, sem perder conteúdo")
    void reencryptsLegacyRows() {
        Long resumeId = upload();

        // Volta o registro ao estado anterior à V7: conteúdo em claro, sem chave.
        byte[] emClaro = PDF_BYTES;
        jdbc.update("""
                UPDATE resume_content
                   SET file_data = ?, extracted_text = ?, extracted_text_enc = NULL,
                       encryption_key_id = NULL
                 WHERE resume_id = ?
                """, emClaro, "texto legado do curriculo", resumeId);

        reencryptionJob.run();

        byte[] depois = jdbc.queryForObject(
                "SELECT file_data FROM resume_content WHERE resume_id = ?", byte[].class, resumeId);
        String keyId = jdbc.queryForObject(
                "SELECT encryption_key_id FROM resume_content WHERE resume_id = ?",
                String.class, resumeId);
        String legadoRestante = jdbc.queryForObject(
                "SELECT extracted_text FROM resume_content WHERE resume_id = ?",
                String.class, resumeId);

        assertThat(keyId).isEqualTo("v1");
        assertThat(cipher.decrypt(keyId, depois)).isEqualTo(emClaro);
        // A cópia em claro é apagada: manter as duas anularia o ganho.
        assertThat(legadoRestante).isNull();

        byte[] textoCifrado = jdbc.queryForObject(
                "SELECT extracted_text_enc FROM resume_content WHERE resume_id = ?",
                byte[].class, resumeId);
        assertThat(cipher.decryptToString(keyId, textoCifrado)).isEqualTo("texto legado do curriculo");
    }

    @Test
    @DisplayName("recifragem sem pendências não faz nada")
    void reencryptionIsIdempotent() {
        upload();
        reencryptionJob.run();

        Integer pendentes = jdbc.queryForObject(
                "SELECT count(*) FROM resume_content WHERE encryption_key_id IS NULL",
                Integer.class);
        assertThat(pendentes).isZero();
    }
}
