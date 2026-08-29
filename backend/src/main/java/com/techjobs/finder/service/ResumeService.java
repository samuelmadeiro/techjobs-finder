package com.techjobs.finder.service;

import com.techjobs.finder.config.ResumeProperties;
import com.techjobs.finder.dto.resume.ResumeResponse;
import com.techjobs.finder.dto.resume.ResumeUploadResponse;
import com.techjobs.finder.entity.AppUser;
import com.techjobs.finder.entity.ParseStatus;
import com.techjobs.finder.entity.Resume;
import com.techjobs.finder.entity.ResumeItem;
import com.techjobs.finder.entity.ResumeContent;
import com.techjobs.finder.entity.ResumeItemKind;
import com.techjobs.finder.entity.ResumeSkill;
import com.techjobs.finder.entity.Technology;
import com.techjobs.finder.exception.InvalidUploadException;
import com.techjobs.finder.exception.ResourceNotFoundException;
import com.techjobs.finder.mapper.ResumeMapper;
import com.techjobs.finder.security.AuthenticatedUser;
import com.techjobs.finder.security.ResumeCipher;
import com.techjobs.finder.repository.AppUserRepository;
import com.techjobs.finder.repository.ResumeContentRepository;
import com.techjobs.finder.repository.ResumeRepository;
import com.techjobs.finder.repository.TechnologyRepository;
import com.techjobs.finder.service.ResumeParserService.ParsedResume;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ciclo de vida do currículo: valida, extrai, analisa, persiste e apaga.
 *
 * <p>O arquivo é tratado como entrada hostil. O nome enviado pelo cliente nunca vira
 * caminho, o tipo declarado pelo cliente nunca é aceito de cara — quem decide é o
 * conteúdo — e o binário fica no banco, não no sistema de arquivos, de modo que não há
 * caminho interno a vazar nem arquivo servível por engano.
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private static final Tika TIKA = new Tika();

    private final ResumeRepository resumeRepository;
    private final ResumeContentRepository contentRepository;
    private final AppUserRepository userRepository;
    private final TechnologyRepository technologyRepository;
    private final ResumeTextExtractor extractor;
    private final ResumeParserService parser;
    private final ResumeMapper mapper;
    private final ResumeCipher cipher;
    private final ResumeProperties properties;

    public ResumeService(ResumeRepository resumeRepository,
                         ResumeContentRepository contentRepository,
                         AppUserRepository userRepository,
                         TechnologyRepository technologyRepository,
                         ResumeTextExtractor extractor,
                         ResumeParserService parser,
                         ResumeMapper mapper,
                         ResumeCipher cipher,
                         ResumeProperties properties) {
        this.resumeRepository = resumeRepository;
        this.contentRepository = contentRepository;
        this.userRepository = userRepository;
        this.technologyRepository = technologyRepository;
        this.extractor = extractor;
        this.parser = parser;
        this.mapper = mapper;
        this.cipher = cipher;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ upload

    @Transactional
    public ResumeUploadResponse upload(MultipartFile file, AuthenticatedUser current) {
        byte[] content = readAndValidate(file);
        String contentType = detectContentType(content);

        AppUser user = userRepository.findById(current.id())
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", current.id()));
        Resume resume = new Resume(
                user,
                safeDisplayName(file.getOriginalFilename(), contentType),
                contentType,
                content.length,
                sha256(content));

        String extractedText = analyze(resume, content, contentType);
        Resume saved = resumeRepository.save(resume);

        // O arquivo e o texto saem daqui já cifrados: a partir deste ponto, nem o banco nem
        // um backup dele contêm currículo legível.
        ResumeCipher.Encrypted encryptedFile = cipher.encrypt(content);
        ResumeCipher.Encrypted encryptedText = cipher.encrypt(extractedText);
        contentRepository.save(new ResumeContent(saved.getId(), encryptedFile.payload(),
                encryptedText == null ? null : encryptedText.payload(), encryptedFile.keyId()));
        log.info("Currículo {} recebido para o usuário {} ({} bytes, {})",
                saved.getId(), user.getId(), saved.getSizeBytes(), contentType);

        return new ResumeUploadResponse(mapper.toResponse(saved));
    }

    /**
     * Preenche o perfil estruturado a partir do arquivo e devolve o texto extraído.
     * Falha de leitura não perde o upload: o currículo fica salvo com o motivo registrado.
     */
    private String analyze(Resume resume, byte[] content, String contentType) {
        var extraction = extractor.extract(content, contentType);
        if (extraction.failed()) {
            resume.setParseStatus(ParseStatus.FAILED);
            resume.setParseMessage(extraction.error());
            return null;
        }
        if (!extraction.hasText()) {
            resume.setParseStatus(ParseStatus.EMPTY);
            resume.setParseMessage("O arquivo não contém texto selecionável. "
                    + "Se for um PDF digitalizado, envie uma versão com texto.");
            return null;
        }

        String text = extraction.text();

        ParsedResume parsed = parser.parse(text);
        resume.setCandidateName(parsed.candidateName());
        resume.setHeadline(parsed.headline());
        resume.setExperienceLevel(parsed.experienceLevel());
        resume.setExperienceYears(parsed.experienceYears());
        resume.setPreferredWorkModel(parsed.preferredWorkModel());
        resume.setLocation(parsed.location());
        resume.replaceSkills(toSkills(parsed.skillCounts()));
        resume.replaceItems(toItems(parsed));
        resume.setParseStatus(ParseStatus.PARSED);
        resume.setParseMessage(null);
        return text;
    }

    private List<ResumeSkill> toSkills(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return List.of();
        }
        Map<String, Technology> index = technologyRepository.findBySlugIn(counts.keySet()).stream()
                .collect(java.util.stream.Collectors.toMap(Technology::getSlug, tech -> tech));

        List<ResumeSkill> skills = new ArrayList<>();
        counts.forEach((slug, occurrences) -> {
            Technology technology = index.get(slug);
            // Tecnologia do catálogo ainda não semeada no banco: guarda só o rótulo.
            String label = technology != null ? technology.getName() : slug;
            skills.add(new ResumeSkill(technology, label, occurrences));
        });
        return skills;
    }

    private List<ResumeItem> toItems(ParsedResume parsed) {
        List<ResumeItem> items = new ArrayList<>();
        for (ResumeItemKind kind : ResumeItemKind.values()) {
            List<String> texts = parsed.itemsOf(kind);
            for (int i = 0; i < texts.size(); i++) {
                items.add(new ResumeItem(kind, i, texts.get(i)));
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ leitura

    /**
     * Currículo mais recente do usuário autenticado, com o perfil carregado.
     *
     * <p>Duas consultas e não uma: o perfil traz coleções, e {@code LIMIT} junto de join de
     * coleção cortaria linhas no meio. A primeira acha o mais recente, a segunda hidrata.
     */
    @Transactional(readOnly = true)
    public Optional<Resume> currentResume(AuthenticatedUser current) {
        if (current == null) {
            return Optional.empty();
        }
        return resumeRepository.findFirstByUserIdOrderByCreatedAtDesc(current.id())
                .flatMap(resume -> resumeRepository.findWithProfileById(resume.getId()));
    }

    @Transactional(readOnly = true)
    public Optional<ResumeResponse> currentProfile(AuthenticatedUser current) {
        return currentResume(current).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ResumeResponse byId(Long id, AuthenticatedUser current) {
        return mapper.toResponse(ownedResume(id, current));
    }

    // ------------------------------------------------------------------ exclusão

    /** Remove o currículo e o binário. O usuário continua existindo para novos envios. */
    @Transactional
    public void delete(Long id, AuthenticatedUser current) {
        resumeRepository.delete(ownedResume(id, current));
        log.info("Currículo {} excluído a pedido do dono", id);
    }

    /**
     * Só o dono acessa o próprio currículo.
     *
     * <p>O dono vem da sessão validada, nunca do caminho da URL: trocar o id em
     * {@code GET /api/resumes/{id}} não muda quem o servidor acha que está pedindo. E
     * currículo de outra pessoa responde 404, não 403 — confirmar que o recurso existe já
     * seria contar algo sobre ela.
     */
    private Resume ownedResume(Long id, AuthenticatedUser current) {
        return resumeRepository.findWithProfileById(id)
                .filter(resume -> current.owns(resume.getUser().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Currículo", id));
    }

    // ------------------------------------------------------------------ validação

    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("Envie um arquivo de currículo.");
        }
        long max = properties.getMaxFileSize().toBytes();
        if (file.getSize() > max) {
            throw new InvalidUploadException("Arquivo maior que o limite de %d MB."
                    .formatted(properties.getMaxFileSize().toMegabytes()));
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null || !properties.getAllowedExtensions().contains(extension)) {
            throw new InvalidUploadException("Formato não aceito. Envie PDF ou DOCX.");
        }
        try {
            byte[] content = file.getBytes();
            if (content.length == 0) {
                throw new InvalidUploadException("O arquivo enviado está vazio.");
            }
            return content;
        } catch (java.io.IOException e) {
            log.warn("Falha ao ler o multipart do currículo", e);
            throw new InvalidUploadException("Não foi possível ler o arquivo enviado.");
        }
    }

    /** Tipo genérico de pacote OOXML: é ZIP, mas pode ser DOCX, XLSX ou PPTX. */
    private static final Set<String> OOXML_CANDIDATES =
            Set.of("application/x-tika-ooxml", "application/zip", "application/octet-stream");

    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /**
     * O tipo vem do conteúdo, nunca do cabeçalho: renomear um executável para
     * {@code curriculo.pdf} não engana a detecção por assinatura.
     *
     * <p>Quando a detecção só consegue dizer "é um pacote OOXML", a decisão passa para o
     * POI: recusar de imediato reprovaria DOCX legítimos, e aceitar de imediato deixaria
     * passar qualquer ZIP. Abrir o documento responde a pergunta sem abrir mão de nenhum
     * dos dois cuidados.
     */
    private String detectContentType(byte[] content) {
        String detected = TIKA.detect(content);
        if (properties.getAllowedContentTypes().contains(detected)) {
            return detected;
        }
        if (OOXML_CANDIDATES.contains(detected)
                && properties.getAllowedContentTypes().contains(DOCX)
                && extractor.isReadableDocx(content)) {
            return DOCX;
        }
        throw new InvalidUploadException("O conteúdo do arquivo não é um PDF nem um DOCX válido.");
    }

    /**
     * Nome apenas para exibição: sem diretório, sem caractere de caminho e com a
     * extensão derivada do tipo real. Nunca é usado para abrir ou gravar nada.
     */
    private String safeDisplayName(String original, String contentType) {
        String base = original == null ? "curriculo" : original;
        base = base.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        if (base.isBlank()) {
            base = "curriculo";
        }
        if (base.length() > 100) {
            base = base.substring(0, 100);
        }
        String extension = "application/pdf".equals(contentType) ? "pdf" : "docx";
        return base + "." + extension;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
