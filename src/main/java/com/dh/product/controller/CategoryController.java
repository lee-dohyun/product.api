package com.dh.product.controller;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.ProductDtos.CategoryCreateRequest;
import com.dh.product.dto.ProductDtos.CategoryResponse;
import com.dh.product.dto.ProductDtos.CategoryUpdateRequest;
import com.dh.product.service.CategoryService;

import jakarta.validation.Valid;

/**
 * 카테고리 조회(공개) + 관리(staff 전용).
 *
 * <p>인증은 이 컨트롤러가 아니라 {@code AdminAuthInterceptor} 가 건다 - GET 만 공개이고
 * 나머지 메서드는 staff realm 토큰 + {@code PRODUCT_MANAGER} 역할을 요구한다.
 * {@code WebConfig.addInterceptors} 가 {@code /api/categories/**} 를 이미 등록해 두었으므로
 * 아래 PUT/DELETE 도 별도 배선 없이 같은 검사를 받는다.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // posselect-shell Header 위젯이 브라우저에서 직접 호출한다 — 카테고리는 자주 안 바뀌므로
    // 5분 캐시를 걸어서 매 페이지 로드마다 이 엔드포인트를 때리지 않게 한다(Next.js fetch revalidate
    // 대신 표준 HTTP 캐시로 같은 효과를 냄). 응답은 여전히 평면 목록이고 parentId/sortOrder만
    // 추가됐다 - 기존 소비자(위젯)는 이 필드를 무시하면 그대로 동작한다.
    //
    // 이 5분 캐시 때문에 관리자가 방금 고친 순서가 헤더에 바로 안 보일 수 있다. 정상 동작이다 -
    // 짧은 TTL 을 없애면 페이지 로드마다 이 API 를 때리게 되므로 그대로 둔다.
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list(
            @RequestHeader(value = "X-Channel", defaultValue = "1") Long channelId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(categoryService.list(channelId));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(
            @RequestHeader(value = "X-Channel", defaultValue = "1") Long channelId,
            @Valid @RequestBody CategoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(channelId, request));
    }

    /**
     * 이름/상위/노출순서를 한 번에 교체한다(부분 수정 아님 - {@code CategoryUpdateRequest} 주석 참고).
     * 채널은 경로의 카테고리가 이미 갖고 있으므로 헤더로 받지 않는다.
     */
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return ResponseEntity.ok(categoryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
