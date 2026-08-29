package com.techjobs.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techjobs.finder.PostgresIntegrationTest;
import com.techjobs.finder.repository.AppUserRepository;
import com.techjobs.finder.repository.ResumeRepository;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.service.AuthenticationService.IssuedSession;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retenção de currículo contra o banco real.
 *
 * <p>São dois deletes em massa escritos à mão, e o que precisa ser provado é justamente o
 * que só o banco responde: se o {@code ON DELETE CASCADE} leva junto o arquivo e as skills,
 * se o {@code NOT EXISTS} do usuário órfão enxerga o currículo que acabou de sair, e se
 * quem está dentro do prazo continua intacto.
 */
class ResumeRetentionIntegrationTest extends PostgresIntegrationTest {

    private static final byte[] PDF_BYTES =
            ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                    .getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    @Transactional
    @DisplayName("currículo vencido sai com o arquivo; o do prazo fica")
    void purgesOnlyExpiredResumes() {
        AuthenticatedUser antigoDono = upload();
        AuthenticatedUser recenteDono = upload();

        // O teste lê por JDBC o que o Hibernate ainda tem em memória; sem o flush, as
        // asserções olhariam para um banco que ainda não recebeu os inserts.
        resumeRepository.flush();

        Long antigo = resumeIdOf(antigoDono);
        Long recente = resumeIdOf(recenteDono);
        backdate(antigo, 200);

        Instant threshold = Instant.now().minus(180, ChronoUnit.DAYS);
        int removidos = resumeRepository.deleteOlderThan(threshold);

        assertThat(removidos).isEqualTo(1);
        // Conferido por JDBC, e não por findById: delete em massa não avisa o contexto de
        // persistência, que devolveria a entidade já apagada do banco.
        assertThat(countResume(antigo)).isZero();
        assertThat(countResume(recente)).isEqualTo(1);
        // O binário mora em outra tabela: só o cascade do banco o remove.
        assertThat(countContent(antigo)).isZero();
        assertThat(countContent(recente)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("usuário antigo sem currículo é removido; quem ainda tem currículo fica")
    void purgesOnlyAbandonedUsers() {
        AuthenticatedUser semCurriculo = upload();
        AuthenticatedUser comCurriculo = upload();

        Long descartavel = resumeIdOf(semCurriculo);
        backdateUsers(200);
        resumeRepository.deleteById(descartavel);
        resumeRepository.flush();

        int removidos = userRepository.deleteAbandonedOlderThan(Instant.now().minus(180, ChronoUnit.DAYS));

        assertThat(removidos).isEqualTo(1);
        // JDBC de novo: delete em massa nao avisa o contexto de persistencia.
        assertThat(countUser(semCurriculo)).isZero();
        assertThat(countUser(comCurriculo)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("usuário recente sem currículo é preservado")
    void keepsRecentUsers() {
        AuthenticatedUser dono = upload();
        resumeRepository.deleteById(resumeIdOf(dono));
        resumeRepository.flush();

        int removidos = userRepository.deleteAbandonedOlderThan(Instant.now().minus(180, ChronoUnit.DAYS));

        assertThat(removidos).isZero();
        assertThat(countUser(dono)).isEqualTo(1);
    }

    /**
     * Abre uma sessao anonima e envia um curriculo por ela, devolvendo a identidade -
     * exatamente o caminho que o usuario percorre.
     */
    private AuthenticatedUser upload() {
        IssuedSession session = authenticationService.openAnonymousSession("teste");
        var file = new MockMultipartFile("file", "curriculo.pdf", "application/pdf", PDF_BYTES);
        resumeService.upload(file, session.user());
        return session.user();
    }

    private Long resumeIdOf(AuthenticatedUser owner) {
        return jdbc.queryForObject(
                "SELECT r.id FROM resume r WHERE r.user_id = ?", Long.class, owner.id());
    }

    private void backdate(Long resumeId, int days) {
        jdbc.update("UPDATE resume SET created_at = NOW() - (? || ' days')::interval WHERE id = ?",
                days, resumeId);
    }

    private void backdateUsers(int days) {
        jdbc.update("UPDATE app_user SET created_at = NOW() - (? || ' days')::interval", days);
    }

    private int countUser(AuthenticatedUser user) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE id = ?", Integer.class, user.id());
        return count == null ? 0 : count;
    }

    private int countResume(Long resumeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM resume WHERE id = ?", Integer.class, resumeId);
        return count == null ? 0 : count;
    }

    private int countContent(Long resumeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM resume_content WHERE resume_id = ?", Integer.class, resumeId);
        return count == null ? 0 : count;
    }
}
