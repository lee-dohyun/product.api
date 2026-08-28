package com.dh.product.service;

/**
 * 카테고리 계층 규칙 위반. 클라이언트가 고칠 수 있는 잘못된 요청이므로 400 으로 매핑한다
 * ({@code ApiExceptionHandler}).
 *
 * <p>범용 {@link IllegalArgumentException} 을 쓰지 않고 전용 예외를 둔 이유: 이 저장소에는
 * 이미 {@code WishlistService} 가 "상품 없음"을 {@code IllegalArgumentException} 으로 던지고
 * 있어서, 그 타입에 전역 핸들러를 붙이면 이번 작업과 무관한 엔드포인트의 응답 코드가 같이
 * 바뀐다. 범위를 이 예외 하나로 좁힌다.
 */
public class CategoryHierarchyException extends RuntimeException {

    public CategoryHierarchyException(String message) {
        super(message);
    }
}
