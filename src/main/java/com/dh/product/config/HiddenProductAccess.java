package com.dh.product.config;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Component;

import com.dh.product.domain.ProductStatus;
import com.dh.product.service.ProductService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 숨김 상품(LIVE 가 아닌 상품) 공개 조회 차단의 단일 판정 지점(product.api#74).
 *
 * <p>상품 단건/옵션·SKU/고시 항목 GET 은 {@link AdminAuthInterceptor} 가 검증 없이 통과시키는
 * 공개 경로다. 여기서 "LIVE 가 아니면 PRODUCT_MANAGER staff 토큰이 있어야 보인다"를 한 곳에서
 * 판정한다 - 컨트롤러마다 따로 두면 새 조회 경로를 만들 때 빠뜨린다(리뷰에서 실제로 두 곳이 빠져 있었다).
 */
@Component
public class HiddenProductAccess {

    /** AdminAuthInterceptor 의 /api/products 쓰기 역할과 같다. */
    private static final String PRODUCT_MANAGER = "PRODUCT_MANAGER";

    private final AdminJwtVerifier adminJwtVerifier;
    private final ProductService productService;

    public HiddenProductAccess(AdminJwtVerifier adminJwtVerifier, ProductService productService) {
        this.adminJwtVerifier = adminJwtVerifier;
        this.productService = productService;
    }

    /** GET 은 인터셉터가 토큰을 보지 않으므로 여기서 직접 확인한다. 없거나 무효면 공개 조회다. */
    public boolean canSeeHidden(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        AdminPrincipal admin = adminJwtVerifier.verify(header.substring("Bearer ".length()));
        return admin != null && admin.hasAnyRole(PRODUCT_MANAGER);
    }

    /**
     * 비공개 상품이면 404 로 끝낸다 - 403 은 "그 id 에 상품이 있다"를 드러낸다(캐논 §보안).
     * 상태는 캐시된 단건 조회({@code product:{id}})에서 읽으므로 추가 쿼리가 거의 없다.
     */
    public void requireVisible(Long productId, HttpServletRequest request) {
        requireVisible(productId, productService.getProduct(productId).status(), request);
    }

    public void requireVisible(Long productId, String status, HttpServletRequest request) {
        if (!ProductStatus.LIVE.name().equals(status) && !canSeeHidden(request)) {
            throw new NoSuchElementException("product not found: " + productId);
        }
    }
}
