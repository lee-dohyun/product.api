package com.dh.product.config;

import java.util.Set;

/**
 * staff realm JWT 에서 뽑아낸 관리자 신원.
 *
 * <p>이전에는 {@code AdminJwtVerifier} 가 email 문자열만 돌려줬고 호출부는 그 값이
 * null 이 아닌지만 봤다. 그래서 <b>staff realm 에 로그인만 되면 역할과 무관하게 모든
 * 관리 API 가 열렸다</b>(product.api#25). 역할을 함께 실어 나르기 위해 신원을 타입으로 만든다.
 */
public record AdminPrincipal(String email, Set<String> roles) {

    /** 모든 관리 기능을 쓸 수 있는 상위 역할. 개별 역할 검사에서 항상 통과한다. */
    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    public boolean hasAnyRole(String... required) {
        if (roles.contains(SYSTEM_ADMIN)) {
            return true;
        }
        for (String role : required) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
