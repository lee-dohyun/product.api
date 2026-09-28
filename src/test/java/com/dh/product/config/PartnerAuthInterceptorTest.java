package com.dh.product.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerStatus;
import com.dh.product.repository.SellerRepository;

/** product.api#75 - 파트너 API 는 GET 까지 인증하고, 비활성 판매자는 쓰기가 막힌다. */
@ExtendWith(MockitoExtension.class)
class PartnerAuthInterceptorTest {

    @Mock
    private PartnerJwtVerifier verifier;
    @Mock
    private SellerRepository sellerRepository;

    private boolean preHandle(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        return new PartnerAuthInterceptor(verifier, sellerRepository).preHandle(request, response, new Object());
    }

    private static MockHttpServletRequest withToken(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/partner/products");
        request.addHeader("Authorization", "Bearer token");
        return request;
    }

    private void sellerWithStatus(SellerStatus status) {
        Seller seller = new Seller();
        seller.setStatus(status);
        given(verifier.verify("token")).willReturn(new PartnerPrincipal("sub", "p@example.com", 2L));
        given(sellerRepository.findById(2L)).willReturn(Optional.of(seller));
    }

    @Test
    void getWithoutTokenIs401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(new MockHttpServletRequest("GET", "/api/partner/products"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void activeSellerCanWrite() throws Exception {
        sellerWithStatus(SellerStatus.ACTIVE);
        MockHttpServletRequest request = withToken("POST");

        assertThat(preHandle(request, new MockHttpServletResponse())).isTrue();
        assertThat(request.getAttribute(PartnerAuthInterceptor.PRINCIPAL_ATTRIBUTE)).isNotNull();
    }

    @Test
    void suspendedSellerCannotWriteButCanRead() throws Exception {
        sellerWithStatus(SellerStatus.SUSPENDED);

        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        assertThat(preHandle(withToken("POST"), writeResponse)).isFalse();
        assertThat(writeResponse.getStatus()).isEqualTo(403);
        assertThat(preHandle(withToken("GET"), new MockHttpServletResponse())).isTrue();
    }

    @Test
    void unknownSellerIs403() throws Exception {
        given(verifier.verify("token")).willReturn(new PartnerPrincipal("sub", "p@example.com", 99L));
        given(sellerRepository.findById(99L)).willReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(withToken("GET"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void sellerIdClaimAcceptsStringOrNumber() {
        assertThat(PartnerJwtVerifier.parseSellerId("12")).isEqualTo(12L);
        assertThat(PartnerJwtVerifier.parseSellerId(12)).isEqualTo(12L);
        assertThat(PartnerJwtVerifier.parseSellerId("abc")).isNull();
        assertThat(PartnerJwtVerifier.parseSellerId(null)).isNull();
    }
}
