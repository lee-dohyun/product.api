package com.dh.product.controller;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.dh.product.config.HiddenProductAccess;
import com.dh.product.domain.ProductStatus;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductManagementSummary;
import com.dh.product.dto.PolicyDtos.ProductPolicyRequest;
import com.dh.product.dto.PolicyDtos.ProductPolicyResponse;
import com.dh.product.dto.SellerDtos.PublicSellerInfo;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.dto.ProductDtos.ProductUpdateRequest;
import com.dh.product.service.ProductPolicyService;
import com.dh.product.service.ProductService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    static final String TOTAL_COUNT_HEADER = "X-Total-Count";
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final ProductService productService;
    private final HiddenProductAccess hiddenProductAccess;
    private final ProductPolicyService productPolicyService;

    public ProductController(ProductService productService, HiddenProductAccess hiddenProductAccess,
            ProductPolicyService productPolicyService) {
        this.productService = productService;
        this.hiddenProductAccess = hiddenProductAccess;
        this.productPolicyService = productPolicyService;
    }

    /**
     * 공개 목록은 LIVE 만 준다(product.api#74). 관리자 화면(admin.front)이 staff 토큰을 실어 부르면
     * 임시저장·검수 중·판매중지 상품까지 전부 준다.
     *
     * <p>page·size 를 둘 다 안 주면 전부를 돌려준다(기존 소비자 호환). 하나라도 주면 그 쪽만 최신 등록 순으로
     * 주고 조건에 맞는 전체 개수를 {@code X-Total-Count} 헤더에 싣는다(product.api#107). 응답 본문은
     * 두 경우 모두 배열이다.
     */
    @GetMapping
    public ResponseEntity<List<ProductSummaryResponse>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        boolean includeHidden = hiddenProductAccess.canSeeHidden(request);
        if (page == null && size == null) {
            return ResponseEntity.ok(productService.listProducts(categoryId, q, includeHidden));
        }
        int pageNumber = page != null ? page : 0;
        int pageSize = size != null ? size : DEFAULT_PAGE_SIZE;
        if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Page<ProductSummaryResponse> result =
                productService.listProductsPage(categoryId, q, includeHidden, pageNumber, pageSize);
        return ResponseEntity.ok()
                .header(TOTAL_COUNT_HEADER, String.valueOf(result.getTotalElements()))
                .body(result.getContent());
    }

    /**
     * 관리자 상품 목록 - 상태·판매자 포함(admin.front#50). 직원(PRODUCT_MANAGER) 전용이고, 아니면 404 로
     * 경로의 존재 자체를 숨긴다. GET 이라 AdminAuthInterceptor 가 검사하지 않으므로 여기서 판정한다.
     * 리터럴 경로라 아래 {@code /{id}} 보다 우선 매칭된다.
     */
    @GetMapping("/manage")
    public List<ProductManagementSummary> manage(
            @RequestParam(required = false) String status, HttpServletRequest request) {
        if (!hiddenProductAccess.canSeeHidden(request)) {
            throw new NoSuchElementException("not found");
        }
        return productService.listForManagement(status != null ? ProductStatus.valueOf(status) : null);
    }

    /**
     * 캐시(product:{id})에는 상태와 무관하게 들어가고 노출 판정은 캐시 뒤에서 한다 - 캐시 키를
     * 호출자별로 나누지 않기 위해서다.
     */
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id, HttpServletRequest request) {
        ProductResponse product = productService.getProduct(id);
        hiddenProductAccess.requireVisible(id, product.status(), request);
        return product;
    }

    /**
     * 상품 상세의 판매자 정보(product.front#36). 상품과 같은 노출 규칙 — 비공개 상품이면 비직원에게 404.
     * 상태는 캐시된 단건 조회에서 읽고, 판매자 정보는 캐시하지 않는다(판매자가 정보를 고치면 바로 반영).
     */
    @GetMapping("/{id}/seller")
    public PublicSellerInfo seller(@PathVariable Long id, HttpServletRequest request) {
        hiddenProductAccess.requireVisible(id, productService.getProduct(id).status(), request);
        return productService.publicSellerInfoOf(id);
    }

    /**
     * 상품 판매 정책 공개 조회(product.api#79) — 배송비·반품비·출고일은 청약 전 제공 정보다.
     * 상품과 같은 노출 규칙(비공개면 비직원 404).
     */
    @GetMapping("/{id}/policy")
    public ProductPolicyResponse policy(@PathVariable Long id, HttpServletRequest request) {
        hiddenProductAccess.requireVisible(id, productService.getProduct(id).status(), request);
        return productPolicyService.get(id);
    }

    /**
     * 관리자 판매 정책 전체 교체(admin.front#53). 파트너 API(/api/partner/.../policy)와 달리 판매 중 상품도
     * 고칠 수 있다 — 직원 권한이고, 관리자 등록(1P) 상품은 검수를 거치지 않는다. 인증·역할(PRODUCT_MANAGER)은
     * AdminAuthInterceptor 가 /api/products/** 의 non-GET 에 건다. 모순된 입력은 400(ProductPolicyService).
     */
    @PutMapping("/{id}/policy")
    public ProductPolicyResponse replacePolicy(@PathVariable Long id, @Valid @RequestBody ProductPolicyRequest request) {
        return productPolicyService.replace(id, request);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
