package com.techjobs.finder.service;

import com.techjobs.finder.config.ResumeProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Extrai texto de PDF e DOCX.
 *
 * <p>O arquivo é entrada não confiável: a leitura acontece em memória, sem gravar nada
 * no disco, e qualquer falha da biblioteca vira {@link ExtractionResult} com erro, nunca
 * exceção propagada para o usuário. Documentos protegidos por senha e PDFs só de imagem
 * caem no caso "sem texto".
 */
@Service
public class ResumeTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResumeTextExtractor.class);

    private static final String PDF = "application/pdf";
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ResumeProperties properties;

    public ResumeTextExtractor(ResumeProperties properties) {
        this.properties = properties;
    }

    /**
     * @param text  texto extraído, possivelmente vazio
     * @param error mensagem exibível quando a extração falhou; nulo em caso de sucesso
     */
    public record ExtractionResult(String text, String error) {

        public boolean failed() {
            return error != null;
        }

        public boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    /**
     * Confirma se o conteúdo abre como documento Word.
     *
     * <p>A detecção por assinatura às vezes só consegue dizer "é um pacote OOXML", sem
     * distinguir DOCX de XLSX ou PPTX — todos são ZIP com a mesma cara. Nesses casos a
     * prova definitiva é tentar abrir: se o POI monta o documento, é um DOCX de verdade.
     */
    public boolean isReadableDocx(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            return document.getDocument() != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public ExtractionResult extract(byte[] content, String contentType) {
        try {
            String text = switch (contentType) {
                case PDF -> fromPdf(content);
                case DOCX -> fromDocx(content);
                default -> null;
            };
            if (text == null) {
                return new ExtractionResult("", "Formato de arquivo não suportado.");
            }
            return new ExtractionResult(trim(normalize(text)), null);
        } catch (IOException | RuntimeException e) {
            // Arquivo corrompido, protegido por senha ou fora do padrão: log interno,
            // mensagem neutra para o usuário.
            log.warn("Falha ao extrair texto do currículo ({})", contentType, e);
            return new ExtractionResult("", "Não foi possível ler o conteúdo do arquivo.");
        }
    }

    private String fromPdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private String fromDocx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /** Uniformiza quebras de linha e remove espaçamento redundante, preservando as linhas. */
    private String normalize(String raw) {
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\u00a0]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String trim(String text) {
        int max = properties.getMaxTextLength();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
