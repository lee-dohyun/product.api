package com.dh.product.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

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
 * <p>캐시를 새로 추가하면 여기에 그 캐시의 왕복 케이스도 추가할 것.
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
}
