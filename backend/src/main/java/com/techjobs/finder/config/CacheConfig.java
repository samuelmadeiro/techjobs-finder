package com.techjobs.finder.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import reactor.util.annotation.NonNull;

/**
 * Cache da aplicação.
 *
 * <p>Com {@code REDIS_HOST} definido o cache é distribuído: sobrevive a reinício e é
 * compartilhado entre instâncias. Sem ele, cai para Caffeine em memória — a aplicação
 * funciona igual, só sem essas duas propriedades. Nenhum código de serviço sabe qual
 * dos dois está ativo.
 *
 * <p>O cache de resultados de busca não passa por aqui: ele é persistente e vive em
 * {@code search_cache_entry}, porque perder a marca de "já consultei essa combinação"
 * significa martelar as fontes de novo — exatamente o que não pode acontecer.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Linguagens, tecnologias e empresas com contagem de vagas. */
    public static final String CATALOG_CACHE = "catalog";

    /** Fontes e status da última coleta. */
    public static final String SOURCES_CACHE = "sources";

    /**
     * Páginas de resultado de busca já montadas.
     *
     * <p>Guarda apenas o DTO de resposta — nada de entidade JPA, e nada que pertença a uma
     * pessoa: só entra aqui a resposta de quem não tem currículo associado, que é idêntica
     * para qualquer visitante. Ver {@code JobSearchService#cacheableKey}.
     */
    public static final String SEARCH_CACHE = "search";

    private static final List<String> CACHES = List.of(CATALOG_CACHE, SOURCES_CACHE, SEARCH_CACHE);

    /**
     * Cache indisponível não pode virar erro para o usuário.
     *
     * <p>O padrão do Spring é propagar a exceção, o que transformaria um Redis fora do
     * ar em 500 numa requisição que o banco atenderia sem problema. Aqui a falha vira
     * log e a chamada segue para a origem.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(@Nonnull RuntimeException exception, @NonNull Cache cache,@NonNull Object key) {
                log.warn("Falha ao ler do cache '{}' (chave {}); consultando a origem",
                        cache.getName(), key, exception);
            }

            @Override
            public void handleCachePutError(@NonNull RuntimeException exception,@NonNull Cache cache,@NonNull Object key,
                                            Object value) {
                log.warn("Falha ao gravar no cache '{}' (chave {})", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheEvictError(@NonNull RuntimeException exception,@NonNull Cache cache,@NonNull Object key) {
                log.warn("Falha ao invalidar o cache '{}' (chave {})", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(@NonNull RuntimeException exception,@NonNull Cache cache) {
                log.warn("Falha ao limpar o cache '{}'", cache.getName(), exception);
            }
        };
    }

    @Bean
    @ConditionalOnExpression("!'${spring.data.redis.host:}'.isEmpty()")
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory,
                                          CacheProperties properties) {
        RedisCacheConfiguration base = baseConfiguration(properties);

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        for (String cache : CACHES) {
            perCache.put(cache, base.entryTtl(properties.ttlFor(cache)));
        }

        log.info("Cache distribuído ativo no Redis (prefixo '{}')", properties.getKeyPrefix());
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                // Só os caches declarados aqui existem; um nome errado em @Cacheable
                // falha na subida em vez de criar um cache silencioso que ninguém limpa.
                .initialCacheNames(Set.copyOf(CACHES))
                .disableCreateOnMissingCache()
                .build();
    }

    private @NonNull RedisCacheConfiguration baseConfiguration(@NonNull CacheProperties properties) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getDefaultTtl())
                // Nada de gravar null: um catálogo vazio por falha momentânea ficaria
                // preso até o TTL vencer.
                .disableCachingNullValues()
                .prefixCacheNameWith(properties.getKeyPrefix() + ":")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(cacheObjectMapper())));
    }

    /**
     * JSON e não serialização Java: o conteúdo fica legível no {@code redis-cli}, não
     * exige {@code Serializable} nos DTOs e não quebra quando um campo é adicionado.
     *
     * <p>A informação de tipo é gravada junto porque os valores são listas genéricas —
     * sem ela não dá para reconstruir {@code List<TechnologyResponse>} na leitura. O
     * validador restringe a desserialização às classes do próprio projeto.
     */
    private @NonNull ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .addModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .allowIfSubType("com.techjobs.finder.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    /**
     * Sem Redis configurado, o cache é local ao processo.
     *
     * <p>A condição é o complemento exato da do Redis, e não um
     * {@code @ConditionalOnMissingBean}: dentro de uma mesma classe de configuração a
     * ordem de avaliação entre os dois não é garantida, e o resultado dependeria da
     * ordem dos métodos no arquivo.
     */
    @Bean
    @ConditionalOnExpression("'${spring.data.redis.host:}'.isEmpty()")
    public @NonNull CacheManager caffeineCacheManager(@NonNull CacheProperties properties) {
        log.info("Redis não configurado; usando cache em memória");
        CaffeineCacheManager manager = new CaffeineCacheManager(CACHES.toArray(String[]::new));
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(properties.getDefaultTtl()));

        // O Caffeine aplica um TTL só para todo o gerenciador; os caches que pedem
        // tempo diferente do padrão são registrados à parte.
        for (String cache : CACHES) {
            Duration ttl = properties.ttlFor(cache);
            if (!ttl.equals(properties.getDefaultTtl())) {
                manager.registerCustomCache(cache, Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(ttl)
                        .build());
            }
        }
        return manager;
    }
}
