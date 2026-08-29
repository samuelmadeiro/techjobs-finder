package com.techjobs.finder.service;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.entity.ResumeItemKind;
import com.techjobs.finder.entity.WorkModel;
import com.techjobs.finder.util.Text;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Converte o texto solto do currículo em um perfil estruturado.
 *
 * <p>A leitura é feita por seções, não por palavras-chave soltas: o texto é dividido nos
 * títulos usuais ("Experiência", "Formação", "Certificações", "Projetos") e cada bloco é
 * interpretado no seu próprio contexto. Isso evita o erro clássico de classificar como
 * formação uma linha que só menciona "universidade" dentro de uma experiência.
 *
 * <p>Só o nível de experiência usa também o texto inteiro, porque "5 anos de experiência"
 * costuma aparecer no resumo profissional, fora de qualquer seção.
 */
@Service
public class ResumeParserService {

    /** Títulos de seção reconhecidos, em português e inglês, já normalizados. */
    private static final Map<ResumeItemKind, List<String>> SECTION_TITLES = new EnumMap<>(Map.of(
            ResumeItemKind.EXPERIENCE, List.of(
                    "experiencia profissional", "experiencias profissionais", "experiencia",
                    "experiencias", "historico profissional", "atuacao profissional",
                    "work experience", "professional experience", "employment history", "experience"),
            ResumeItemKind.EDUCATION, List.of(
                    "formacao academica", "formacao", "escolaridade", "educacao",
                    "education", "academic background"),
            ResumeItemKind.CERTIFICATION, List.of(
                    "certificacoes", "certificacao", "cursos e certificacoes", "cursos",
                    "certifications", "certificates", "licenses"),
            ResumeItemKind.PROJECT, List.of(
                    "projetos", "projetos pessoais", "portfolio",
                    "projects", "personal projects", "side projects")));

    /** Títulos que encerram um bloco sem iniciar outro de interesse. */
    private static final List<String> NEUTRAL_TITLES = List.of(
            "resumo", "resumo profissional", "objetivo", "sobre mim", "perfil",
            "habilidades", "competencias", "conhecimentos", "tecnologias", "skills",
            "summary", "about", "profile", "technical skills", "idiomas", "languages",
            "contato", "contatos", "contact", "links", "voluntariado", "premios");

    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*(?:\\+)?\\s*(?:anos?|years?)\\s*(?:de\\s*|of\\s*)?(?:experiencia|experience|atuacao)?");

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{7,}\\d");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private static final Pattern SENIOR = Pattern.compile(
            "(?<![a-z])(senior|sr\\.?|especialista|staff|principal|tech lead|arquiteto)(?![a-z])");
    private static final Pattern MID = Pattern.compile("(?<![a-z])(pleno|mid[- ]level|intermediario)(?![a-z])");
    private static final Pattern JUNIOR = Pattern.compile("(?<![a-z])(junior|jr\\.?|entry[- ]level)(?![a-z])");
    private static final Pattern TRAINEE = Pattern.compile("(?<![a-z])trainee(?![a-z])");
    private static final Pattern INTERN = Pattern.compile("(?<![a-z])(estagi[aáo]\\w*|intern|internship)(?![a-z])");

    private static final Pattern REMOTE_PREFERENCE = Pattern.compile(
            "(?<![a-z])(remoto|remote|home office|anywhere)(?![a-z])");
    private static final Pattern HYBRID_PREFERENCE = Pattern.compile("(?<![a-z])(hibrido|hybrid)(?![a-z])");

    /** "João Pessoa - PB", "São Paulo, SP", "Recife/PE": cidade seguida de sigla de estado. */
    private static final Pattern CITY_STATE = Pattern.compile(
            "([\\p{L}][\\p{L}.'\\s]{2,40}?)\\s*[-,/]\\s*([A-Z]{2})(?![A-Za-z])");

    /** Linha de item de lista: começa com marcador ou tem tamanho de frase. */
    private static final int MIN_ITEM_LENGTH = 8;
    private static final int MAX_ITEM_LENGTH = 500;
    private static final int MAX_ITEMS_PER_SECTION = 15;

    private final TechnologyCatalog catalog;

    public ResumeParserService(TechnologyCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Perfil extraído do currículo.
     *
     * @param skillCounts slug da tecnologia -> número de ocorrências no texto
     */
    public record ParsedResume(
            String candidateName,
            String headline,
            ExperienceLevel experienceLevel,
            Integer experienceYears,
            WorkModel preferredWorkModel,
            String location,
            Map<String, Integer> skillCounts,
            Map<ResumeItemKind, List<String>> items) {

        public List<String> itemsOf(ResumeItemKind kind) {
            return items.getOrDefault(kind, List.of());
        }
    }

    public ParsedResume parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new ParsedResume(null, null, ExperienceLevel.UNKNOWN, null, null, null,
                    Map.of(), Map.of());
        }

        List<String> lines = rawText.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        Map<ResumeItemKind, List<String>> sections = splitSections(lines);
        Integer years = extractYears(rawText);
        ExperienceLevel level = detectLevel(rawText, sections.get(ResumeItemKind.EXPERIENCE), years);

        return new ParsedResume(
                extractName(lines),
                extractHeadline(lines),
                level,
                years,
                detectPreferredWorkModel(rawText),
                extractLocation(lines),
                catalog.detectWithCounts(rawText),
                sections);
    }

    // ------------------------------------------------------------------ seções

    /** Percorre as linhas trocando de seção sempre que encontra um título conhecido. */
    private Map<ResumeItemKind, List<String>> splitSections(List<String> lines) {
        Map<ResumeItemKind, List<String>> sections = new LinkedHashMap<>();
        ResumeItemKind current = null;

        for (String line : lines) {
            ResumeItemKind heading = matchSectionTitle(line);
            if (heading != null) {
                current = heading;
                continue;
            }
            if (isNeutralTitle(line)) {
                current = null;
                continue;
            }
            if (current == null || !isUsableItem(line)) {
                continue;
            }
            List<String> bucket = sections.computeIfAbsent(current, k -> new ArrayList<>());
            if (bucket.size() < MAX_ITEMS_PER_SECTION) {
                bucket.add(Text.truncate(stripBullet(line), MAX_ITEM_LENGTH));
            }
        }
        return sections;
    }

    /**
     * Um título de seção é uma linha curta cujo texto é exatamente um dos rótulos
     * conhecidos. Exigir a linha inteira evita tratar "Tenho experiência com Java" como
     * cabeçalho de "Experiência".
     */
    private ResumeItemKind matchSectionTitle(String line) {
        String normalized = normalizeHeading(line);
        if (normalized == null) {
            return null;
        }
        for (Map.Entry<ResumeItemKind, List<String>> entry : SECTION_TITLES.entrySet()) {
            if (entry.getValue().contains(normalized)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean isNeutralTitle(String line) {
        String normalized = normalizeHeading(line);
        return normalized != null && NEUTRAL_TITLES.contains(normalized);
    }

    private String normalizeHeading(String line) {
        if (line.length() > 45) {
            return null;
        }
        String normalized = Text.normalize(line.replaceAll("[:•\\-–—*#]+", " "));
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private boolean isUsableItem(String line) {
        return line.length() >= MIN_ITEM_LENGTH;
    }

    private String stripBullet(String line) {
        return line.replaceFirst("^[•▪◦*\\-–—·]\\s*", "").trim();
    }

    // ------------------------------------------------------------------ campos

    /**
     * O nome quase sempre é a primeira linha significativa e não contém e-mail,
     * telefone, URL nem dois-pontos.
     */
    private String extractName(List<String> lines) {
        for (String line : lines.subList(0, Math.min(5, lines.size()))) {
            String candidate = line.trim();
            if (candidate.length() < 4 || candidate.length() > 60 || candidate.contains(":")) {
                continue;
            }
            if (EMAIL.matcher(candidate).find() || URL.matcher(candidate).find()
                    || PHONE.matcher(candidate).find()) {
                continue;
            }
            long words = candidate.split("\\s+").length;
            if (words >= 2 && words <= 6 && candidate.matches("[\\p{L}\\s.'-]+")) {
                return Text.truncate(candidate, 160);
            }
        }
        return null;
    }

    /** Primeira linha após o nome que pareça um cargo/resumo, não um dado de contato. */
    private String extractHeadline(List<String> lines) {
        for (String line : lines.subList(0, Math.min(8, lines.size()))) {
            String candidate = line.trim();
            if (candidate.length() < 10 || candidate.length() > 200) {
                continue;
            }
            if (EMAIL.matcher(candidate).find() || URL.matcher(candidate).find()
                    || PHONE.matcher(candidate).find() || isNeutralTitle(candidate)) {
                continue;
            }
            if (matchSectionTitle(candidate) != null) {
                continue;
            }
            if (!candidate.equals(extractName(lines))) {
                return Text.truncate(candidate, 500);
            }
        }
        return null;
    }

    /**
     * Localização do candidato, procurada só no cabeçalho do currículo: mais abaixo, uma
     * sigla de dois caracteres depois de um traço é quase sempre outra coisa (um cargo,
     * uma sigla de empresa) e não a cidade de quem escreveu.
     */
    private String extractLocation(List<String> lines) {
        for (String line : lines.subList(0, Math.min(8, lines.size()))) {
            if (line.length() > 200) {
                continue;
            }
            // A linha de contato costuma juntar tudo ("email | cidade - UF"). Remover
            // e-mail e URL evita casar pedaço de endereço eletrônico como cidade, sem
            // descartar a linha — que é justamente onde a cidade costuma estar.
            String cleaned = URL.matcher(EMAIL.matcher(line).replaceAll(" ")).replaceAll(" ");
            Matcher matcher = CITY_STATE.matcher(cleaned);
            while (matcher.find()) {
                String city = matcher.group(1).trim();
                if (city.length() >= 3) {
                    return Text.truncate(city + " - " + matcher.group(2), 255);
                }
            }
        }
        return null;
    }

    /** Maior valor encontrado: "3 anos de Java" e "5 anos de experiência" -> 5. */
    private Integer extractYears(String rawText) {
        String normalized = Text.normalize(rawText);
        if (normalized == null) {
            return null;
        }
        Matcher matcher = YEARS.matcher(normalized);
        Integer best = null;
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value > 0 && value <= 50 && (best == null || value > best)) {
                best = value;
            }
        }
        return best;
    }

    /**
     * Prioriza o rótulo explícito de senioridade citado nas experiências; se não houver,
     * deduz pelos anos declarados. Sem nenhum dos dois, devolve UNKNOWN em vez de chutar.
     */
    private ExperienceLevel detectLevel(String rawText, List<String> experiences, Integer years) {
        String scope = experiences == null || experiences.isEmpty()
                ? rawText
                : String.join("\n", experiences);
        String normalized = Text.normalize(scope);

        if (normalized != null) {
            if (SENIOR.matcher(normalized).find()) {
                return ExperienceLevel.SENIOR;
            }
            if (MID.matcher(normalized).find()) {
                return ExperienceLevel.MID;
            }
            if (JUNIOR.matcher(normalized).find()) {
                return ExperienceLevel.JUNIOR;
            }
            if (TRAINEE.matcher(normalized).find()) {
                return ExperienceLevel.TRAINEE;
            }
            if (INTERN.matcher(normalized).find()) {
                return ExperienceLevel.INTERNSHIP;
            }
        }
        return fromYears(years);
    }

    private ExperienceLevel fromYears(Integer years) {
        if (years == null) {
            return ExperienceLevel.UNKNOWN;
        }
        if (years >= 6) {
            return ExperienceLevel.SENIOR;
        }
        if (years >= 3) {
            return ExperienceLevel.MID;
        }
        return years >= 1 ? ExperienceLevel.JUNIOR : ExperienceLevel.TRAINEE;
    }

    private WorkModel detectPreferredWorkModel(String rawText) {
        String normalized = Text.normalize(rawText);
        if (normalized == null) {
            return null;
        }
        if (HYBRID_PREFERENCE.matcher(normalized).find()) {
            return WorkModel.HYBRID;
        }
        return REMOTE_PREFERENCE.matcher(normalized).find() ? WorkModel.REMOTE : null;
    }
}
