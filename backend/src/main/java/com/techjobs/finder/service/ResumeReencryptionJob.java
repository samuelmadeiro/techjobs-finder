package com.techjobs.finder.service;

import com.techjobs.finder.entity.ResumeContent;
import com.techjobs.finder.repository.ResumeContentRepository;
import com.techjobs.finder.security.ResumeCipher;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converte para cifrado o que ficou em claro antes desta versão.
 *
 * <p>Sem isto, a criptografia valeria só para currículos novos e o acervo antigo seguiria
 * exposto em qualquer backup — que é justamente o cenário que a Fase 1 veio resolver.
 *
 * <p>Em lotes pequenos e periódicos, não tudo de uma vez: recifrar milhares de arquivos de
 * megabytes em uma transação só seguraria conexão, encheria memória e deixaria um bloqueio
 * longo em cima de uma tabela que o upload também escreve. O índice parcial criado na V7
 * faz a busca pelas linhas pendentes custar praticamente nada, e ele deixa de ser usado
 * sozinho quando não sobrar nenhuma.
 *
 * <p>Também é o mecanismo de rotação de chave: apontar {@code active-key-id} para a chave
 * nova e limpar {@code encryption_key_id} das linhas a converter faz este mesmo laço
 * recifrar o acervo com a chave nova, sem código adicional.
 */
@Component
@ConditionalOnProperty(prefix = "techjobs.encryption", name = "reencryption-enabled",
        havingValue = "true", matchIfMissing = true)
public class ResumeReencryptionJob {

    private static final Logger log = LoggerFactory.getLogger(ResumeReencryptionJob.class);

    /** Lote pequeno: arquivos de até 5 MB, e a tarefa roda de novo em minutos. */
    private static final int BATCH_SIZE = 25;

    private final ResumeContentRepository contentRepository;
    private final ResumeCipher cipher;

    public ResumeReencryptionJob(ResumeContentRepository contentRepository, ResumeCipher cipher) {
        this.contentRepository = contentRepository;
        this.cipher = cipher;
    }

    @Scheduled(fixedDelayString = "${techjobs.encryption.reencryption-interval:5m}",
            initialDelayString = "${techjobs.encryption.reencryption-initial-delay:1m}")
    @Transactional
    public void run() {
        List<ResumeContent> pending = contentRepository.findPendingEncryption(Limit.of(BATCH_SIZE));
        if (pending.isEmpty()) {
            return;
        }

        int converted = 0;
        for (ResumeContent content : pending) {
            try {
                ResumeCipher.Encrypted file = cipher.encrypt(content.getFileData());
                String legacyText = content.getLegacyExtractedText();
                ResumeCipher.Encrypted text = legacyText == null
                        ? null
                        : cipher.encrypt(legacyText.getBytes(StandardCharsets.UTF_8));
                content.replaceWithEncrypted(file.payload(),
                        text == null ? null : text.payload(), file.keyId());
                converted++;
            } catch (RuntimeException e) {
                // Um registro problemático não pode travar a fila inteira; o id vai para o
                // log, o conteúdo não.
                log.error("Falha ao cifrar o conteúdo do currículo {}", content.getResumeId(), e);
            }
        }
        log.info("Recifragem: {} de {} registro(s) do lote convertidos", converted, pending.size());
    }
}
