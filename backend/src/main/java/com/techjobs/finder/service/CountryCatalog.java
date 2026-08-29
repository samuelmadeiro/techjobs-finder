package com.techjobs.finder.service;

import com.techjobs.finder.util.Text;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Países que a busca aceita, e como reconhecer um deles no texto que as fontes mandam.
 *
 * <p>Duas coisas moram aqui de propósito. A <strong>lista</strong>, porque o valor que
 * trafega na API precisa ser estável — {@code country=BR}, nunca {@code country=Brasil} — e
 * porque o frontend não pode ter a sua própria cópia da lista: bandeira e nome saem daqui,
 * por {@code GET /api/countries}. E a <strong>classificação</strong>, porque as fontes não
 * mandam código de país: mandam {@code "London"}, {@code "Berlin"}, {@code "Anywhere"},
 * {@code "LATAM"} ou string vazia. Alguém precisa transformar isso em código, uma vez só, na
 * ingestão.
 *
 * <p>A regra de ouro do classificador é errar sempre para o mesmo lado: o que ele não
 * reconhece vira {@link #GLOBAL}, o balde sem país definido, que aparece em qualquer busca.
 * Assim uma vaga jamais é escondida do país errado; no pior caso ela fica visível a mais.
 * O contrário — chutar um país e sumir com a vaga para todo mundo — seria dano silencioso.
 */
@Component
public class CountryCatalog {

    /**
     * Sem país definido: vaga remota global, região multipaís ("LATAM", "Europe") ou
     * localização que não dá para atribuir a um país. {@code ZZ} é reservado pela
     * ISO-3166 para uso próprio, então não colide com país nenhum.
     */
    public static final String GLOBAL = "ZZ";

    /**
     * Um país da lista.
     *
     * @param code      ISO-3166 alpha-2, o valor que trafega na API
     * @param name      nome em português, exibido pela interface
     * @param flag      bandeira em emoji, para a interface não manter a sua própria tabela
     * @param jobicyGeo slug aceito pelo parâmetro {@code geo} da API do Jobicy, ou
     *                  {@code null} quando aquela fonte não reconhece este país. Foi
     *                  verificado chamando a API: {@code united-kingdom} e {@code india},
     *                  por exemplo, não são aceitos — {@code uk} é.
     * @param aliases   como o país aparece escrito nas fontes
     * @param cities    cidades que identificam o país sozinhas, porque boa parte das fontes
     *                  manda só a cidade ({@code "London"}, {@code "Berlin"})
     */
    public record Country(String code, String name, String flag, String jobicyGeo,
                          List<String> aliases, List<String> cities) {
    }

    /**
     * A lista é curta e explícita. Nada de importar um dataset com 249 países: só entra
     * país que as fontes atuais realmente alimentam, e cada linha custa aliases e cidades
     * escritos à mão. Ampliar é acrescentar uma linha aqui — o resto do sistema não muda.
     */
    private static final List<Country> COUNTRIES = List.of(
            new Country("BR", "Brasil", "🇧🇷", "brazil",
                    List.of("brasil", "brazil"),
                    List.of("sao paulo", "rio de janeiro", "belo horizonte", "curitiba",
                            "porto alegre", "florianopolis", "recife", "brasilia", "campinas")),
            new Country("US", "Estados Unidos", "🇺🇸", "usa",
                    List.of("united states", "estados unidos", "usa"),
                    List.of("new york", "san francisco", "seattle", "austin", "boston",
                            "chicago", "los angeles", "denver", "atlanta", "miami")),
            new Country("CA", "Canadá", "🇨🇦", "canada",
                    List.of("canada"),
                    List.of("toronto", "vancouver", "montreal", "ottawa", "calgary",
                            "edmonton", "winnipeg", "halifax")),
            new Country("PT", "Portugal", "🇵🇹", "portugal",
                    List.of("portugal"),
                    List.of("lisboa", "lisbon", "porto", "braga", "coimbra", "aveiro")),
            new Country("GB", "Reino Unido", "🇬🇧", "uk",
                    List.of("united kingdom", "reino unido", "uk", "england", "scotland",
                            "wales", "great britain"),
                    List.of("london", "manchester", "edinburgh", "birmingham", "bristol",
                            "leeds", "glasgow", "cambridge")),
            new Country("DE", "Alemanha", "🇩🇪", "germany",
                    List.of("germany", "deutschland", "alemanha"),
                    List.of("berlin", "munich", "munchen", "hamburg", "frankfurt", "cologne",
                            "koln", "stuttgart", "dusseldorf", "leipzig")),
            new Country("ES", "Espanha", "🇪🇸", "spain",
                    List.of("spain", "espana", "espanha"),
                    List.of("madrid", "barcelona", "valencia", "sevilla", "bilbao", "malaga")),
            new Country("FR", "França", "🇫🇷", "france",
                    List.of("france", "franca"),
                    List.of("paris", "lyon", "marseille", "toulouse", "bordeaux", "nantes",
                            "lille")),
            new Country("AU", "Austrália", "🇦🇺", "australia",
                    List.of("australia"),
                    List.of("sydney", "melbourne", "brisbane", "perth", "adelaide",
                            "canberra")));

    private final Map<String, Country> byCode;

    public CountryCatalog() {
        Map<String, Country> index = new LinkedHashMap<>();
        COUNTRIES.forEach(country -> index.put(country.code(), country));
        this.byCode = Map.copyOf(index);
    }

    public List<Country> all() {
        return COUNTRIES;
    }

    public Optional<Country> find(String code) {
        return code == null ? Optional.empty()
                : Optional.ofNullable(byCode.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    public boolean isSupported(String code) {
        return find(code).isPresent();
    }

    /** Códigos aceitos, em texto, para a mensagem de erro de validação. */
    public String supportedCodes() {
        return String.join(", ", COUNTRIES.stream().map(Country::code).toList());
    }

    /**
     * Descobre o país de uma vaga a partir do texto que a fonte forneceu.
     *
     * <p>Nome de país primeiro, cidade depois, e nada de sigla de duas letras solta.
     * {@code "us"}, {@code "de"} e {@code "ca"} aparecem no meio de texto normal — "Rio DE
     * Janeiro" viraria Alemanha —, então só o texto que é <em>exatamente</em> o código conta
     * como código. Termo de região ({@code "Anywhere"}, {@code "LATAM"}, {@code "Europe"})
     * não casa com nada e cai em {@link #GLOBAL}, que é o resultado certo: são vários países
     * ou nenhum em particular.
     *
     * @param location texto de localização da vaga
     * @param country  texto de país já extraído pela normalização (último segmento da
     *                 localização), quando houver
     */
    public String classify(String location, String country) {
        String haystack = normalizedHaystack(location, country);
        if (haystack.isBlank()) {
            return GLOBAL;
        }
        // Texto que é só o código ("BR", "us"): a fonte já respondeu a pergunta.
        Optional<Country> exact = find(haystack);
        if (exact.isPresent()) {
            return exact.get().code();
        }

        for (Country candidate : COUNTRIES) {
            for (String alias : candidate.aliases()) {
                if (containsToken(haystack, alias)) {
                    return candidate.code();
                }
            }
        }
        for (Country candidate : COUNTRIES) {
            for (String city : candidate.cities()) {
                if (containsToken(haystack, city)) {
                    return candidate.code();
                }
            }
        }
        return GLOBAL;
    }

    private static String normalizedHaystack(String location, String country) {
        String joined = (location == null ? "" : location) + " " + (country == null ? "" : country);
        String normalized = Text.normalize(joined);
        return normalized == null ? "" : normalized;
    }

    /**
     * Correspondência por palavra inteira, não por trecho.
     *
     * <p>{@code contains} puro faria {@code "us"} casar dentro de {@code "austin"} e
     * {@code "br"} dentro de {@code "brighton"} — o tipo de erro que manda uma vaga de
     * Londres para a busca do Brasil e some com ela do Reino Unido.
     */
    private static boolean containsToken(String haystack, String token) {
        int from = 0;
        while (true) {
            int index = haystack.indexOf(token, from);
            if (index < 0) {
                return false;
            }
            boolean startsClean = index == 0 || !isWordChar(haystack.charAt(index - 1));
            int end = index + token.length();
            boolean endsClean = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (startsClean && endsClean) {
                return true;
            }
            from = index + 1;
        }
    }

    private static boolean isWordChar(char value) {
        return Character.isLetterOrDigit(value);
    }
}
