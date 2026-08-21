package com.dh.product.config;

/**
 * 캐시 이름 상수.
 *
 * <p>캐시 이름이 문자열 리터럴로 여기저기 흩어져 있으면, 무효화 쪽 이름만 오타가 나거나
 * 한쪽만 바뀌어도 아무 오류 없이 "무효화되지 않는 캐시"가 된다. 실제로 메인 페이지 캐시 3종은
 * 무효화 코드 자체가 없어 쓰기 후 TTL 만료까지 낡은 값을 응답했다(product.api#24).
 * 정의(@Cacheable)·무효화(@CacheEvict)·설정(RedisConfig)이 모두 이 상수를 참조하게 해서
 * 세 곳이 갈라지지 않게 한다.
 *
 * <p>애노테이션 인자로 쓰이므로 각 값은 컴파일 타임 상수여야 한다 —
 * 배열 상수는 애노테이션에 넣을 수 없어 사용처에서 개별 상수를 나열한다.
 */
public final class CacheNames {

    /** 단일 상품 상세. 키는 상품 id. */
    public static final String PRODUCT = "product";

    /** 메인 페이지 - 베스트 목록. 상품명/대표가격/총재고/대표이미지를 담는다. */
    public static final String MAIN_BEST = "main-best";

    /** 메인 페이지 - 신상품 목록. */
    public static final String MAIN_NEW = "main-new";

    /** 메인 페이지 - 카테고리별 목록. */
    public static final String MAIN_BY_CATEGORY = "main-by-category";

    private CacheNames() {
    }
}
