package com.dh.product.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import com.dh.product.dto.BannerDtos.BannerResponse;
import com.dh.product.dto.ProductDtos.CategoryResponse;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 캐시 값 직렬화 왕복 검증.
 *
 * <p>이 테스트가 존재하는 이유: 메인 페이지 캐시 3종이 <b>캐시 히트에서만</b> 500 을 냈다
 * (product.api#33). {@code GenericJackson2JsonRedisSerializer} 는 non-final 타입에만
 * {@code @class} 를 적는데 DTO 가 record(= 암묵적 final)라 타입 정보가 안 적혔고, 읽을 때
 * 그걸 요구하다가 터졌다. 쓰기는 멀쩡했으므로 "저장은 되는데 못 읽는" 상태였다.
 *
 * <p>캐시 미스에서는 절대 재현되지 않아서, 로컬에서도 CI 에서도 정상으로 보였다. 화면 쪽은
 * store.front 가 예외를 잡아 빈 배열을 돌려주므로 에러가 아니라 "섹션이 그냥 없는 것"으로
 * 보였다. 그래서 왕복을 직접 거는 이 테스트가 필요하다 - Redis 없이도 성립한다.
 *
 * <p>이 주석은 소용이 없었다 — #33 을 고친 지 한 시간 만에 배너 캐시(#37)가 설정 등록 없이
 * 추가되어 같은 방식으로 터졌다. 그래서 사람이 기억해야 하는 자리에는 아래
 * {@code 모든_캐시가_명시적_설정을_갖는다} 검사를 뒀다.
 */
class MainCacheSerializationTest {

    private final ObjectMapper mapper = RedisConfig.cacheObjectMapper();

    private static ProductSummaryResponse summary(long id) {
        return new ProductSummaryResponse(id, 9101L, "포스베이직 오버핏 반팔 티셔츠",
                new BigDecimal("22100.00"), 40, "https://image.posselect.com/cdn/products/fashion-01-1.png");
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(RedisSerializer<Object> serializer, Object value) {
        return (T) serializer.deserialize(serializer.serialize(value));
    }

    @Test
    void 베스트_신상품_캐시값이_왕복해도_같다() {
        List<ProductSummaryResponse> original = List.of(summary(9001L), summary(9002L));

        List<ProductSummaryResponse> restored =
                roundTrip(RedisConfig.productSummaryListSerializer(mapper), original);

        assertThat(restored).isEqualTo(original);
    }

    /**
     * 키가 String 인지까지 확인한다. JSON 오브젝트의 키는 항상 문자열이라 Map&lt;Long,...&gt; 으로
     * 선언하면 왕복 결과가 선언 타입과 어긋나 응답을 쓸 때 ClassCastException 이 난다.
     */
    @Test
    void 카테고리별_캐시값이_왕복해도_같고_키는_문자열이다() {
        Map<String, List<ProductSummaryResponse>> original = new LinkedHashMap<>();
        original.put("9001", List.of(summary(9001L), summary(9002L)));
        original.put("9002", List.of());

        Map<String, List<ProductSummaryResponse>> restored =
                roundTrip(RedisConfig.byCategorySerializer(mapper), original);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.keySet()).allSatisfy(key -> assertThat(key).isInstanceOf(String.class));
    }

    /** 상품 상세 캐시는 LocalDateTime 을 담는다 - JavaTimeModule 이 빠지면 여기서 걸린다. */
    @Test
    void 상품상세_캐시값이_왕복해도_같다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 21, 21, 0);
        ProductResponse original = new ProductResponse(9001L,
                new CategoryResponse(9101L, "상의", 9001L),
                "포스베이직 오버핏 반팔 티셔츠", "설명",
                new BigDecimal("22100.00"), 40, List.of(), List.of(), List.of(), now, now);

        ProductResponse restored = roundTrip(RedisConfig.productSerializer(mapper), original);

        assertThat(restored).isEqualTo(original);
    }

    /** 배너 캐시는 List<BannerResponse> 를 담는다 (product.api#37). */
    @Test
    void 배너_캐시값이_왕복해도_같다() {
        List<BannerResponse> original = List.of(
                new BannerResponse(9001L, "검증된 상품만 엄선했습니다", "오픈 기념 특별전",
                        "https://image.posselect.com/cdn/banners/v2/hero-1.png", "/", "var(--color-accent)"),
                new BannerResponse(9003L, "오늘의 특가", null, null, "/", "var(--color-highlight-600)"));

        List<BannerResponse> restored = roundTrip(RedisConfig.bannerListSerializer(mapper), original);

        assertThat(restored).isEqualTo(original);
    }

    /**
     * <b>이 테스트가 이 파일의 핵심이다.</b> 캐시를 추가하면서 RedisConfig 등록을 빠뜨리면
     * 조용히 cacheDefaults(= ProductResponse 고정 타입)로 떨어져서, 캐시 히트에서만 500 이 난다.
     * 미스에서는 재현되지 않아 로컬에서도 CI 에서도 정상으로 보인다.
     *
     * <p>같은 사고가 두 번(#33, #37) 났으므로 "새 캐시를 추가하면 잊지 말 것" 같은 주석에
     * 기대지 않는다. CacheNames 에 상수를 추가하고 cacheSpecs 에 등록하지 않으면 여기서 막힌다.
     */
    @Test
    void 모든_캐시가_명시적_설정을_갖는다() throws IllegalAccessException {
        List<String> declared = new ArrayList<>();
        for (Field field : CacheNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                declared.add((String) field.get(null));
            }
        }

        assertThat(declared).isNotEmpty();
        assertThat(RedisConfig.cacheSpecs(mapper).keySet())
                .as("CacheNames 에 있는데 RedisConfig.cacheSpecs 에 등록되지 않은 캐시")
                .containsExactlyInAnyOrderElementsOf(declared);
    }
}
