package com.dh.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import com.dh.product.config.AdminJwtVerifier;
import com.dh.product.config.HiddenProductAccess;
import com.dh.product.domain.ProductStatus;
import com.dh.product.config.AdminPrincipal;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.service.ProductService;

/**
 * product.api#74 - 상품 단건 조회는 LIVE 가 아니면 비직원에게 404 다.
 * 404 인 이유: 403 은 "그 id 에 상품이 있다"는 사실을 노출한다(캐논 §보안).
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerVisibilityTest {

    @Mock
    private ProductService productService;
    @Mock
    private AdminJwtVerifier verifier;

    private ProductController controller;

    @BeforeEach
    void setUp() {
        controller = new ProductController(productService, new HiddenProductAccess(verifier, productService));
    }

    private static ProductResponse response(String status) {
        return new ProductResponse(7L, null, "p", null, null, 0, List.of(), List.of(), List.of(),
                null, null, null, null, 0, null, false, null, 1L, "포스셀렉트", status);
    }

    @Test
    void anonymousGetsNotFoundForDraft() {
        given(productService.getProduct(7L)).willReturn(response("DRAFT"));

        assertThatThrownBy(() -> controller.get(7L, new MockHttpServletRequest()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anonymousSeesLiveProduct() {
        given(productService.getProduct(7L)).willReturn(response("LIVE"));

        assertThat(controller.get(7L, new MockHttpServletRequest()).status()).isEqualTo("LIVE");
    }

    @Test
    void productManagerSeesDraft() {
        given(productService.getProduct(7L)).willReturn(response("DRAFT"));
        given(verifier.verify("token")).willReturn(new AdminPrincipal("pm@posselect.com", Set.of("PRODUCT_MANAGER")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");

        assertThat(controller.get(7L, request).status()).isEqualTo("DRAFT");
    }

    @Test
    void staffWithoutProductRoleDoesNotSeeDraft() {
        given(productService.getProduct(7L)).willReturn(response("DRAFT"));
        given(verifier.verify("token")).willReturn(new AdminPrincipal("om@posselect.com", Set.of("ORDER_MANAGER")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");

        assertThatThrownBy(() -> controller.get(7L, request)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listForProductManagerIncludesHidden() {
        given(verifier.verify("token")).willReturn(new AdminPrincipal("pm@posselect.com", Set.of("PRODUCT_MANAGER")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");

        controller.list(null, null, request);

        verify(productService).listProducts(null, null, true);
    }

    @Test
    void anonymousListExcludesHidden() {
        controller.list(null, null, new MockHttpServletRequest());

        verify(productService).listProducts(null, null, false);
    }

    /** admin.front#50 - 관리자 목록(판매자·상태 포함)은 직원 전용이다. 비직원에게는 존재 자체를 숨긴다(404). */
    @Test
    void manageListIsNotFoundForAnonymous() {
        assertThatThrownBy(() -> controller.manage(null, new MockHttpServletRequest()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void manageListForProductManagerPassesStatusFilter() {
        given(verifier.verify("token")).willReturn(new AdminPrincipal("pm@posselect.com", Set.of("PRODUCT_MANAGER")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");

        controller.manage("DRAFT", request);

        verify(productService).listForManagement(ProductStatus.DRAFT);
    }

    /** product.front#36 - 비공개 상품의 판매자 정보도 비직원에게는 404 다. */
    @Test
    void sellerInfoOfDraftIsNotFoundForAnonymous() {
        given(productService.getProduct(7L)).willReturn(response("DRAFT"));

        assertThatThrownBy(() -> controller.seller(7L, new MockHttpServletRequest()))
                .isInstanceOf(NoSuchElementException.class);
        org.mockito.Mockito.verify(productService, org.mockito.Mockito.never()).publicSellerInfoOf(7L);
    }
}
