package com.techjobs.finder.service;

import com.techjobs.finder.entity.ExperienceLevel;
import com.techjobs.finder.util.Text;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deduz o nível da vaga combinando vários sinais em vez de aceitar o primeiro que aparecer.
 *
 * <p>Cada sinal encontrado soma pontos para um nível; vence o nível com maior pontuação.
 * A força do sinal reflete o quanto ele costuma ser confiável:
 *
 * <ol>
 *   <li><b>Título</b> (peso 10) — quando o cargo diz "Júnior", é isso mesmo.</li>
 *   <li><b>Campo da fonte</b> (peso 8) — {@code jobLevel}, {@code seniority}, {@code job_type}.</li>
 *   <li><b>Anos de experiência</b> (peso 6) — "mínimo de 5 anos", "3-5 years".</li>
 *   <li><b>Expressões típicas</b> (peso 4) — "bolsa auxílio", "recém-formado", "liderar o time".</li>
 *   <li><b>Palavra solta na descrição</b> (peso 2) — o sinal mais fraco, só desempata.</li>
 * </ol>
 *
 * <p>Sem nenhum sinal o resultado é {@link ExperienceLevel#UNKNOWN}: chutar um nível seria pior
 * que admitir desconhecimento, porque a relevância já trata UNKNOWN como acerto parcial.
 */
@Component
public class ExperienceLevelDetector {

    private static final double WEIGHT_TITLE = 10;
    private static final double WEIGHT_HINT = 8;
    private static final double WEIGHT_YEARS = 6;
    private static final double WEIGHT_PHRASE = 4;
    private static final double WEIGHT_KEYWORD = 2;

    /** Trecho inicial da descrição analisado; o topo concentra requisitos e senioridade. */
    private static final int DESCRIPTION_WINDOW = 4000;

    // Palavras de nível. O texto chega normalizado (minúsculo, sem acento).
    private static final Pattern INTERN = Pattern.compile(
            "(?<![a-z])(estagio|estagiario|estagiaria|intern|internship|co-op|coop|trainee program de estagio)(?![a-z])");
    private static final Pattern TRAINEE = Pattern.compile("(?<![a-z])(trainee)(?![a-z])");
    private static final Pattern JUNIOR = Pattern.compile(
            "(?<![a-z])(junior|jr|jr\\.|entry.level|iniciante|nivel i|associate)(?![a-z])");
    private static final Pattern MID = Pattern.compile(
            "(?<![a-z])(pleno|mid.level|midlevel|mid|intermediate|intermediario|nivel ii)(?![a-z])");
    private static final Pattern SENIOR = Pattern.compile(
            "(?<![a-z])(senior|sr|sr\\.|especialista|specialist|staff|principal|tech lead|team lead|lead|"
                    + "arquiteto|architect|head of|nivel iii)(?![a-z])");

    /** Expressões que indicam o nível sem citar o rótulo. */
    private static final List<PhraseSignal> PHRASES = List.of(
            phrase(ExperienceLevel.INTERNSHIP,
                    "bolsa auxilio", "bolsa-auxilio", "cursando (graduacao|superior|faculdade|ensino)",
                    "previsao de (formatura|conclusao)", "estar matriculado", "must be enrolled",
                    "currently enrolled", "pursuing a (bachelor|degree)", "vaga para estudante",
                    "cursando o \\d+", "semestre em diante"),
            phrase(ExperienceLevel.TRAINEE,
                    "programa de trainee", "graduate program", "new grad", "recem.formad",
                    "recently graduated", "recem.graduad", "formacao concluida ha pouco",
                    "primeiro emprego", "programa de formacao"),
            phrase(ExperienceLevel.JUNIOR,
                    "sem experiencia previa", "no prior experience", "nao exigimos experiencia",
                    "primeira experiencia", "inicio de carreira", "early career",
                    "0 a 2 anos", "ate 2 anos de experiencia"),
            phrase(ExperienceLevel.MID,
                    "experiencia solida", "solid experience", "autonomia para",
                    "conhecimento intermediario", "proficiency in"),
            phrase(ExperienceLevel.SENIOR,
                    "liderar (o time|a equipe|times|equipes)", "lead a team", "mentorar", "mentoria",
                    "mentoring", "definir a arquitetura", "own the architecture", "extensive experience",
                    "deep expertise", "referencia tecnica", "influenciar decisoes tecnicas"));

    /** "mínimo de 5 anos", "5+ anos", "at least 3 years", "3-5 years of experience". */
    private static final Pattern YEARS_RANGE = Pattern.compile(
            "(\\d{1,2})\\s*(?:a|to|-|ate)\\s*(\\d{1,2})\\s*(?:\\+)?\\s*(?:anos|years)");
    private static final Pattern YEARS_SINGLE = Pattern.compile(
            "(?:no minimo|minimo de|pelo menos|at least|mais de|over|acima de)?\\s*"
                    + "(\\d{1,2})\\s*(?:\\+|ou mais|or more)?\\s*(?:anos|years)"
                    + "(?:\\s*(?:de|of)\\s*(?:experiencia|experience|atuacao))?");

    public ExperienceLevel detect(String title, String hint, String description, List<String> tags) {
        Map<ExperienceLevel, Double> scores = new EnumMap<>(ExperienceLevel.class);

        addLabelSignals(scores, Text.normalize(title), WEIGHT_TITLE);
        addLabelSignals(scores, Text.normalize(hint), WEIGHT_HINT);
        if (tags != null && !tags.isEmpty()) {
            addLabelSignals(scores, Text.normalize(String.join(" ", tags)), WEIGHT_HINT);
        }

        String body = Text.normalize(Text.truncate(description, DESCRIPTION_WINDOW));
        if (body != null && !body.isBlank()) {
            addYearsSignal(scores, body);
            addPhraseSignals(scores, body);
            addLabelSignals(scores, body, WEIGHT_KEYWORD);
        }
        return best(scores);
    }

    /** Anos de experiência exigidos, quando o texto informa. Útil em log e depuração. */
    public Integer extractYearsOfExperience(String description) {
        String body = Text.normalize(Text.truncate(description, DESCRIPTION_WINDOW));
        return body == null ? null : minimumYears(body);
    }

    private void addLabelSignals(Map<ExperienceLevel, Double> scores, String text, double weight) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (INTERN.matcher(text).find()) {
            add(scores, ExperienceLevel.INTERNSHIP, weight);
        }
        if (TRAINEE.matcher(text).find()) {
            add(scores, ExperienceLevel.TRAINEE, weight);
        }
        if (JUNIOR.matcher(text).find()) {
            add(scores, ExperienceLevel.JUNIOR, weight);
        }
        if (MID.matcher(text).find()) {
            add(scores, ExperienceLevel.MID, weight);
        }
        if (SENIOR.matcher(text).find()) {
            add(scores, ExperienceLevel.SENIOR, weight);
        }
    }

    private void addPhraseSignals(Map<ExperienceLevel, Double> scores, String body) {
        for (PhraseSignal signal : PHRASES) {
            for (Pattern pattern : signal.patterns()) {
                if (pattern.matcher(body).find()) {
                    add(scores, signal.level(), WEIGHT_PHRASE);
                    break;
                }
            }
        }
    }

    private void addYearsSignal(Map<ExperienceLevel, Double> scores, String body) {
        Integer years = minimumYears(body);
        if (years == null) {
            return;
        }
        add(scores, fromYears(years), WEIGHT_YEARS);
    }

    /**
     * Menor exigência de anos citada no texto. Descrições costumam listar vários números
     * ("3 anos de Java, 5 anos de cloud"); o menor é o piso real de entrada.
     */
    private Integer minimumYears(String body) {
        List<Integer> found = new ArrayList<>();

        Matcher range = YEARS_RANGE.matcher(body);
        while (range.find()) {
            found.add(parse(range.group(1)));
        }
        Matcher single = YEARS_SINGLE.matcher(body);
        while (single.find()) {
            found.add(parse(single.group(1)));
        }
        return found.stream().filter(value -> value != null && value >= 0 && value <= 30)
                .min(Integer::compareTo)
                .orElse(null);
    }

    private static Integer parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Faixas usuais do mercado brasileiro de TI. */
    private static ExperienceLevel fromYears(int years) {
        if (years <= 0) {
            return ExperienceLevel.JUNIOR;
        }
        if (years <= 2) {
            return ExperienceLevel.JUNIOR;
        }
        if (years <= 5) {
            return ExperienceLevel.MID;
        }
        return ExperienceLevel.SENIOR;
    }

    private static void add(Map<ExperienceLevel, Double> scores, ExperienceLevel level, double weight) {
        scores.merge(level, weight, Double::sum);
    }

    /** Em empate, o nível mais baixo vence: errar para menos evita esconder vaga de iniciante. */
    private static ExperienceLevel best(Map<ExperienceLevel, Double> scores) {
        ExperienceLevel winner = ExperienceLevel.UNKNOWN;
        double bestScore = 0;
        for (Map.Entry<ExperienceLevel, Double> entry : scores.entrySet()) {
            double score = entry.getValue();
            if (score > bestScore
                    || (score == bestScore && winner != ExperienceLevel.UNKNOWN
                        && entry.getKey().rank() < winner.rank())) {
                winner = entry.getKey();
                bestScore = score;
            }
        }
        return winner;
    }

    private record PhraseSignal(ExperienceLevel level, List<Pattern> patterns) {
    }

    private static PhraseSignal phrase(ExperienceLevel level, String... regexes) {
        List<Pattern> patterns = new ArrayList<>();
        for (String regex : regexes) {
            patterns.add(Pattern.compile(regex));
        }
        return new PhraseSignal(level, patterns);
    }
}
