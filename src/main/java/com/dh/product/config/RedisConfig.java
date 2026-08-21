package com.dh.product.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.dh.product.dto.BannerDtos.BannerResponse;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 캐시 직렬화 설정.
 *
 * <p><b>모든 캐시는 고정 타입(JavaType) 직렬화기를 쓴다. 기본 타이핑(@class)을 쓰지 않는다.</b>
 * 메인 캐시 3종이 예전에 {@code GenericJackson2JsonRedisSerializer}를 썼는데, 그 직렬화기는
 * {@code activateDefaultTyping(..., NON_FINAL)} 로 동작해서 <b>non-final 타입에만</b> {@code @class}를
 * 적는다. {@code ProductSummaryResponse}는 record 라 암묵적으로 final 이므로 타입 정보가 안 적혔고,
 * 바깥 {@code ArrayList}(non-final)에는 적혔다. 읽을 때 리스트 원소는 {@code Object}로 취급되어
 * 타입 id 를 요구하는데 없으니 터진다:
 *
 * <pre>
 * SerializationException: Could not read JSON: Unexpected token (START_OBJECT),
 *   expected VALUE_STRING: need String, Number of Boolean value that contains type id
 * </pre>
 *
 * <p>증상이 고약한 이유는 <b>캐시 미스에서는 절대 재현되지 않는다</b>는 점이다. 캐시를 비운 직후
 * 첫 요청은 200 이고 그 다음부터 TTL 이 끝날 때까지 전부 500 이었다. 게다가 store.front 는 예외를
 * 잡아 빈 배열을 돌려주므로 화면엔 에러가 아니라 "섹션이 그냥 없는 것"으로 보였다 (product.api#33).
 *
 * <p>고정 타입은 타입 id 자체가 필요 없으므로 이 부류의 문제가 원천적으로 생기지 않는다.
 * 캐시를 추가할 때도 같은 방식을 따를 것 — 새 캐시마다 그 캐시가 담는 타입을 여기에 명시한다.
 * 왕복 검증은 {@code MainCacheSerializationTest} 가 지킨다.
 */
@Configuration
public class RedisConfig {

    /** 캐시 값 직렬화에 쓰는 ObjectMapper. 테스트가 같은 설정을 재현할 수 있게 여기서 만든다. */
    public static ObjectMapper cacheObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** 단일 상품 상세({@code product} 캐시)용. */
    public static RedisSerializer<Object> productSerializer(ObjectMapper mapper) {
        return new Jackson2JsonRedisSerializer<>(mapper, mapper.getTypeFactory().constructType(ProductResponse.class));
    }

    /** 베스트/신상품({@code main-best}, {@code main-new})용 — {@code List<ProductSummaryResponse>}. */
    public static RedisSerializer<Object> productSummaryListSerializer(ObjectMapper mapper) {
        return new Jackson2JsonRedisSerializer<>(mapper, summaryListType(mapper));
    }

    /**
     * 카테고리별({@code main-by-category})용 — {@code Map<String, List<ProductSummaryResponse>>}.
     *
     * <p>키가 {@code Long}이 아니라 {@code String}인 것은 실수가 아니다. JSON 오브젝트의 키는 항상
     * 문자열이라 {@code Map<Long, ...>}으로 선언하면 역직렬화 결과가 선언 타입과 어긋나
     * 응답을 쓸 때 {@code String cannot be cast to Number}로 터진다 (product.api#33).
     */
    public static RedisSerializer<Object> byCategorySerializer(ObjectMapper mapper) {
        JavaType type = mapper.getTypeFactory().constructMapType(
                LinkedHashMap.class, mapper.getTypeFactory().constructType(String.class), summaryListType(mapper));
        return new Jackson2JsonRedisSerializer<>(mapper, type);
    }

    /** 메인 배너({@code main-banners})용 — {@code List<BannerResponse>}. */
    public static RedisSerializer<Object> bannerListSerializer(ObjectMapper mapper) {
        return new Jackson2JsonRedisSerializer<>(mapper,
                mapper.getTypeFactory().constructCollectionType(List.class, BannerResponse.class));
    }

    /**
     * 캐시 이름 -> (TTL, 값 직렬화기). <b>{@link CacheNames} 에 상수를 추가하면 여기에도 반드시
     * 등록해야 한다.</b> 등록을 빠뜨리면 조용히 {@code cacheDefaults} 로 떨어지는데, 그 기본
     * 직렬화기는 {@code ProductResponse} 고정 타입이라 다른 타입을 담은 캐시는 히트에서 터진다.
     *
     * <p>실제로 두 번 났다 — product.api#33(메인 3종), #37(배너). 두 번째는 첫 번째를 고친 지
     * 한 시간 만이었고, 그때 남긴 "새 캐시를 추가하면 테스트도 추가할 것"이라는 주석은 소용이
     * 없었다. 그래서 {@code MainCacheSerializationTest} 가 이 맵과 {@link CacheNames} 를
     * 대조해서 누락을 CI 에서 막는다.
     */
    public static Map<String, CacheSpec> cacheSpecs(ObjectMapper mapper) {
        return Map.of(
                CacheNames.PRODUCT, new CacheSpec(Duration.ofMinutes(10), productSerializer(mapper)),
                CacheNames.MAIN_BEST, new CacheSpec(Duration.ofMinutes(5), productSummaryListSerializer(mapper)),
                CacheNames.MAIN_NEW, new CacheSpec(Duration.ofMinutes(5), productSummaryListSerializer(mapper)),
                CacheNames.MAIN_BY_CATEGORY, new CacheSpec(Duration.ofMinutes(10), byCategorySerializer(mapper)),
                CacheNames.MAIN_BANNERS, new CacheSpec(Duration.ofMinutes(10), bannerListSerializer(mapper)));
    }

    /** 캐시 하나의 TTL 과 값 직렬화기. */
    public record CacheSpec(Duration ttl, RedisSerializer<Object> serializer) {
    }

    private static JavaType summaryListType(ObjectMapper mapper) {
        return mapper.getTypeFactory().constructCollectionType(List.class, ProductSummaryResponse.class);
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = cacheObjectMapper();
        Map<String, CacheSpec> specs = cacheSpecs(mapper);

        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(connectionFactory)
                // 기본값은 "등록되지 않은 캐시"를 위한 안전망이 아니다 - 어떤 타입이든 받아주는
                // 직렬화기가 없기 때문이다. 등록 누락은 cacheSpecs 대조 테스트가 막는다.
                .cacheDefaults(baseConfig(specs.get(CacheNames.PRODUCT)));
        specs.forEach((name, spec) -> builder.withCacheConfiguration(name, baseConfig(spec)));
        return builder.build();
    }

    private RedisCacheConfiguration baseConfig(CacheSpec spec) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(spec.ttl())
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(spec.serializer()));
    }
}
