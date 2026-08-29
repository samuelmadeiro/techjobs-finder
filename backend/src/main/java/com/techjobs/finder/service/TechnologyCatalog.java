package com.techjobs.finder.service;

import com.techjobs.finder.entity.TechnologyKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Taxonomia de linguagens e tecnologias, com os apelidos usados pelas fontes.
 * Adicionar uma tecnologia nova é acrescentar uma linha em {@link #ENTRIES}.
 */
@Component
public class TechnologyCatalog {

    /**
     * @param slug    identificador canônico usado na API
     * @param name    nome de exibição
     * @param kind    categoria
     * @param aliases variações aceitas no texto das vagas (sempre em minúsculas, sem acento)
     */
    public record Entry(String slug, String name, TechnologyKind kind, List<String> aliases) {
    }

    private static final List<Entry> ENTRIES = List.of(
            // Linguagens
            entry("java", "Java", TechnologyKind.LANGUAGE, "java", "java8", "java11", "java17", "java21"),
            entry("python", "Python", TechnologyKind.LANGUAGE, "python", "py"),
            entry("javascript", "JavaScript", TechnologyKind.LANGUAGE, "javascript", "js", "ecmascript"),
            entry("typescript", "TypeScript", TechnologyKind.LANGUAGE, "typescript", "ts"),
            entry("c", "C", TechnologyKind.LANGUAGE, "c"),
            entry("cpp", "C++", TechnologyKind.LANGUAGE, "c++", "cpp", "cplusplus"),
            entry("csharp", "C#", TechnologyKind.LANGUAGE, "c#", "csharp", "c sharp"),
            entry("go", "Go", TechnologyKind.LANGUAGE, "go", "golang"),
            entry("php", "PHP", TechnologyKind.LANGUAGE, "php"),
            entry("kotlin", "Kotlin", TechnologyKind.LANGUAGE, "kotlin"),
            entry("ruby", "Ruby", TechnologyKind.LANGUAGE, "ruby"),
            entry("swift", "Swift", TechnologyKind.LANGUAGE, "swift"),
            entry("rust", "Rust", TechnologyKind.LANGUAGE, "rust"),
            entry("scala", "Scala", TechnologyKind.LANGUAGE, "scala"),
            entry("elixir", "Elixir", TechnologyKind.LANGUAGE, "elixir"),
            entry("dart", "Dart", TechnologyKind.LANGUAGE, "dart"),
            entry("sql", "SQL", TechnologyKind.LANGUAGE, "sql"),

            // Frameworks e bibliotecas
            entry("spring-boot", "Spring Boot", TechnologyKind.FRAMEWORK, "spring boot", "springboot", "spring-boot"),
            entry("spring", "Spring", TechnologyKind.FRAMEWORK, "spring", "spring framework", "spring mvc"),
            entry("quarkus", "Quarkus", TechnologyKind.FRAMEWORK, "quarkus"),
            entry("hibernate", "Hibernate", TechnologyKind.FRAMEWORK, "hibernate", "jpa"),
            entry("react", "React", TechnologyKind.FRAMEWORK, "react", "reactjs", "react.js"),
            entry("angular", "Angular", TechnologyKind.FRAMEWORK, "angular", "angularjs"),
            entry("vuejs", "Vue.js", TechnologyKind.FRAMEWORK, "vue", "vuejs", "vue.js"),
            entry("nextjs", "Next.js", TechnologyKind.FRAMEWORK, "next.js", "nextjs"),
            entry("nodejs", "Node.js", TechnologyKind.FRAMEWORK, "node", "nodejs", "node.js"),
            entry("express", "Express", TechnologyKind.FRAMEWORK, "express", "expressjs"),
            entry("django", "Django", TechnologyKind.FRAMEWORK, "django"),
            entry("flask", "Flask", TechnologyKind.FRAMEWORK, "flask"),
            entry("fastapi", "FastAPI", TechnologyKind.FRAMEWORK, "fastapi"),
            entry("laravel", "Laravel", TechnologyKind.FRAMEWORK, "laravel"),
            entry("rails", "Ruby on Rails", TechnologyKind.FRAMEWORK, "rails", "ruby on rails"),
            entry("dotnet", ".NET", TechnologyKind.FRAMEWORK, ".net", "dotnet", "asp.net", "aspnet"),
            entry("flutter", "Flutter", TechnologyKind.FRAMEWORK, "flutter"),
            entry("react-native", "React Native", TechnologyKind.FRAMEWORK, "react native", "react-native"),

            // Bancos de dados
            entry("postgresql", "PostgreSQL", TechnologyKind.DATABASE, "postgresql", "postgres", "psql"),
            entry("mysql", "MySQL", TechnologyKind.DATABASE, "mysql", "mariadb"),
            entry("mongodb", "MongoDB", TechnologyKind.DATABASE, "mongodb", "mongo"),
            entry("redis", "Redis", TechnologyKind.DATABASE, "redis"),
            entry("oracle", "Oracle", TechnologyKind.DATABASE, "oracle", "plsql", "pl sql"),
            entry("sqlserver", "SQL Server", TechnologyKind.DATABASE, "sql server", "sqlserver", "t-sql"),
            entry("elasticsearch", "Elasticsearch", TechnologyKind.DATABASE, "elasticsearch", "opensearch"),

            // Cloud
            entry("aws", "AWS", TechnologyKind.CLOUD, "aws", "amazon web services"),
            entry("azure", "Azure", TechnologyKind.CLOUD, "azure"),
            entry("gcp", "GCP", TechnologyKind.CLOUD, "gcp", "google cloud"),

            // Ferramentas e plataformas
            entry("docker", "Docker", TechnologyKind.TOOL, "docker"),
            entry("kubernetes", "Kubernetes", TechnologyKind.TOOL, "kubernetes", "k8s"),
            entry("terraform", "Terraform", TechnologyKind.TOOL, "terraform"),
            entry("kafka", "Kafka", TechnologyKind.TOOL, "kafka"),
            entry("rabbitmq", "RabbitMQ", TechnologyKind.TOOL, "rabbitmq"),
            entry("git", "Git", TechnologyKind.TOOL, "git"),
            entry("jenkins", "Jenkins", TechnologyKind.TOOL, "jenkins"),
            entry("graphql", "GraphQL", TechnologyKind.TOOL, "graphql"),
            entry("linux", "Linux", TechnologyKind.TOOL, "linux"));

    private static final Map<String, Entry> BY_SLUG = new LinkedHashMap<>();
    private static final List<AliasPattern> PATTERNS = new ArrayList<>();

    static {
        for (Entry entry : ENTRIES) {
            BY_SLUG.put(entry.slug(), entry);
            for (String alias : entry.aliases()) {
                PATTERNS.add(AliasPattern.of(alias, entry));
            }
        }
    }

    private static Entry entry(String slug, String name, TechnologyKind kind, String... aliases) {
        return new Entry(slug, name, kind, List.of(aliases));
    }

    public List<Entry> all() {
        return ENTRIES;
    }

    public Entry bySlug(String slug) {
        return BY_SLUG.get(slug);
    }

    public boolean isKnown(String slug) {
        return BY_SLUG.containsKey(slug);
    }

    public List<Entry> byKind(TechnologyKind kind) {
        return ENTRIES.stream().filter(e -> e.kind() == kind).toList();
    }

    /**
     * Extrai as tecnologias citadas no texto da vaga. As tags da fonte pesam igual ao
     * corpo do texto, mas ambos passam pelo mesmo casamento por palavra inteira, para
     * evitar falso positivo como "go" dentro de "going".
     */
    public Set<String> detect(String title, String description, List<String> tags) {
        String haystack = String.join(" \n ",
                nullToEmpty(title),
                nullToEmpty(description),
                tags == null ? "" : String.join(" ", tags));
        String normalized = com.techjobs.finder.util.Text.normalize(haystack);
        if (normalized == null || normalized.isBlank()) {
            return Set.of();
        }

        Set<String> tokens = new HashSet<>(Arrays.asList(normalized.split("[\\s,;/()\\[\\]]+")));
        Set<String> found = new LinkedHashSet<>();
        for (AliasPattern pattern : PATTERNS) {
            if (pattern.matches(normalized, tokens)) {
                found.add(pattern.entry().slug());
            }
        }
        return found;
    }

    /**
     * Mesma detecção de {@link #detect}, mas contando quantas vezes cada tecnologia
     * aparece. O currículo usa a contagem como proxy de ênfase: quem cita "Java" oito
     * vezes provavelmente trabalha com Java, quem cita uma vez pode só ter feito um curso.
     */
    public Map<String, Integer> detectWithCounts(String text) {
        String normalized = com.techjobs.finder.util.Text.normalize(nullToEmpty(text));
        if (normalized == null || normalized.isBlank()) {
            return Map.of();
        }
        List<String> tokenList = Arrays.asList(normalized.split("[\\s,;/()\\[\\]]+"));
        Set<String> tokens = new HashSet<>(tokenList);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AliasPattern pattern : PATTERNS) {
            int occurrences = pattern.count(normalized, tokenList, tokens);
            if (occurrences > 0) {
                counts.merge(pattern.entry().slug(), occurrences, Integer::sum);
            }
        }
        return counts;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Apelidos curtos (1-2 caracteres, como {@code c}, {@code go}, {@code js}) só casam
     * por token exato; os demais casam por expressão com fronteira de palavra.
     */
    private record AliasPattern(String alias, Entry entry, Pattern pattern, boolean tokenOnly) {

        static AliasPattern of(String alias, Entry entry) {
            String normalized = com.techjobs.finder.util.Text.normalize(alias);
            String value = normalized == null || normalized.isBlank() ? alias.toLowerCase() : normalized;
            boolean tokenOnly = value.length() <= 2;
            Pattern compiled = tokenOnly
                    ? null
                    : Pattern.compile("(?<![a-z0-9])" + Pattern.quote(value) + "(?![a-z0-9])");
            return new AliasPattern(value, entry, compiled, tokenOnly);
        }

        boolean matches(String normalizedText, Set<String> tokens) {
            if (tokenOnly) {
                return tokens.contains(alias);
            }
            Matcher matcher = pattern.matcher(normalizedText);
            return matcher.find();
        }

        int count(String normalizedText, List<String> tokenList, Set<String> tokens) {
            if (tokenOnly) {
                return tokens.contains(alias)
                        ? (int) tokenList.stream().filter(alias::equals).count()
                        : 0;
            }
            Matcher matcher = pattern.matcher(normalizedText);
            int total = 0;
            while (matcher.find()) {
                total++;
            }
            return total;
        }
    }
}
