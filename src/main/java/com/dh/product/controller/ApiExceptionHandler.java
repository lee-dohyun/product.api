package com.dh.product.controller;

import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.dh.product.service.InvalidProductPolicyException;
import com.dh.product.service.partner.InvalidPartnerRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.dh.product.service.CategoryHierarchyException;
import com.dh.product.service.offer.InvalidOfferResolveRequestException;
import com.dh.product.service.rag.RagUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /**
     * 상품당 진행 중 검수 1건 제약(V19) 위반만 409 로 바꾼다 - 동시에 두 번 제출한 쪽이 받는다.
     * 다른 무결성 위반까지 409 로 뭉개면 진짜 버그가 "충돌"로 숨으므로 그 외에는 그대로 500 이다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uq_product_submissions_open_per_product")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("이미 검수가 진행 중인 상품입니다.");
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("internal error");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /**
     * 카테고리 계층 규칙 위반(자기 자신/자손을 부모로, 2뎁스 초과)은 클라이언트가 고칠 수 있는
     * 잘못된 요청이므로 400 이다. 이 예외 하나만 매핑하는 이유는 예외 클래스 주석 참고 -
     * 범용 IllegalArgumentException 에 걸면 무관한 엔드포인트의 응답 코드가 같이 바뀐다.
     */
    @ExceptionHandler(InvalidProductPolicyException.class)
    public ResponseEntity<String> handleInvalidPolicy(InvalidProductPolicyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(InvalidPartnerRequestException.class)
    public ResponseEntity<String> handleInvalidPartnerRequest(InvalidPartnerRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(CategoryHierarchyException.class)
    public ResponseEntity<String> handleBadRequest(CategoryHierarchyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    /** {@code /internal/offers/resolve} 의 ids/variantIds 규칙 위반 - 호출자가 고칠 요청 오류라 400. */
    @ExceptionHandler(InvalidOfferResolveRequestException.class)
    public ResponseEntity<String> handleInvalidOfferResolveRequest(InvalidOfferResolveRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(RagUnavailableException.class)
    public ResponseEntity<String> handleRagUnavailable(RagUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }
}
