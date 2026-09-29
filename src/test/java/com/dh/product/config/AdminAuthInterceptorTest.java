package com.dh.product.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * product.api#25 회귀 방지.
 *
 * <p>이 테스트가 없던 동안 인터셉터는 "staff realm 토큰이 유효한가"만 보고 통과시켰다.
 * 인가 판정은 틀려도 조용히 통과하는 영역이라 회귀를 눈으로 잡을 수 없다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthInterceptorTest {

    private static final String BEARER = "Bearer token";

    @Mock
    private AdminJwtVerifier verifier;

    private MockHttpServletRequest write(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader("Authorization", BEARER);
        return request;
    }

    private boolean preHandle(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        return new AdminAuthInterceptor(verifier).preHandle(request, response, new Object());
    }

    @Test
    @DisplayName("PRODUCT_MANAGER 는 상품 쓰기를 통과한다")
    void productManagerPasses() throws Exception {
        given(verifier.verify("token"))
                .willReturn(new AdminPrincipal("pm@posselect.com", Set.of("PRODUCT_MANAGER")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(write("/api/products"), response)).isTrue();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN 은 개별 역할이 없어도 통과한다")
    void systemAdminPasses() throws Exception {
        given(verifier.verify("token"))
                .willReturn(new AdminPrincipal("root@posselect.com", Set.of("SYSTEM_ADMIN")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(write("/api/categories"), response)).isTrue();
    }

    @Test
    @DisplayName("ORDER_MANAGER 는 토큰이 유효해도 상품 쓰기가 거부된다 — 이것이 product.api#25 의 결함이었다")
    void orderManagerIsRejected() throws Exception {
        given(verifier.verify("token"))
                .willReturn(new AdminPrincipal("om@posselect.com", Set.of("ORDER_MANAGER")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(write("/api/products"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("역할이 하나도 없는 staff 토큰도 거부된다")
    void noRolesIsRejected() throws Exception {
        given(verifier.verify("token"))
                .willReturn(new AdminPrincipal("nobody@posselect.com", Set.of()));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(write("/api/products"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("토큰이 없거나 무효하면 거부된다")
    void invalidTokenIsRejected() throws Exception {
        given(verifier.verify("token")).willReturn(null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(write("/api/products"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("GET 은 공개 조회라 토큰 없이 통과한다 (기존 동작 유지)")
    void getStaysPublic() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(request, response)).isTrue();
    }

    /*
     * product.api#74 - 조회(GET)는 원래 전부 공개였다. 그런데 검수 제출(심사 메모·제출자 이메일)과
     * 판매자(사업자 정보·정산 계좌)까지 GET 이 무인증으로 열려 있었다. 이 둘만 조회에도 인증을 요구한다.
     */
    @Test
    @DisplayName("상품 목록 GET 은 토큰 없이도 공개다")
    void productReadIsPublic() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(new MockHttpServletRequest("GET", "/api/products"), response)).isTrue();
    }

    @Test
    @DisplayName("검수 제출 GET 은 토큰이 없으면 거부된다")
    void submissionReadRequiresToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(new MockHttpServletRequest("GET", "/api/submissions"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("판매자 GET 은 토큰이 없으면 거부된다")
    void sellerReadRequiresToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(new MockHttpServletRequest("GET", "/api/sellers/1"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("PRODUCT_MANAGER 는 검수 제출을 조회할 수 있다")
    void productManagerReadsSubmissions() throws Exception {
        given(verifier.verify("token"))
                .willReturn(new AdminPrincipal("pm@posselect.com", Set.of("PRODUCT_MANAGER")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/submissions/3");
        request.addHeader("Authorization", BEARER);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(request, response)).isTrue();
    }

    /** admin.front#53 - 관리자 정책 수정(PUT /api/products/{id}/policy)은 PRODUCT_MANAGER 만. */
    @Test
    @DisplayName("정책 PUT 은 토큰 없으면 거부, ORDER_MANAGER 도 거부")
    void policyPutRequiresProductManager() throws Exception {
        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        assertThat(preHandle(new MockHttpServletRequest("PUT", "/api/products/1/policy"), anonymous)).isFalse();
        assertThat(anonymous.getStatus()).isEqualTo(403);

        given(verifier.verify("token")).willReturn(new AdminPrincipal("om@posselect.com", Set.of("ORDER_MANAGER")));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/products/1/policy");
        request.addHeader("Authorization", BEARER);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
