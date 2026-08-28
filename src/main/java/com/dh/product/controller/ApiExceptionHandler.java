package com.dh.product.controller;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.dh.product.service.CategoryHierarchyException;
import com.dh.product.service.rag.RagUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
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
    @ExceptionHandler(CategoryHierarchyException.class)
    public ResponseEntity<String> handleBadRequest(CategoryHierarchyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(RagUnavailableException.class)
    public ResponseEntity<String> handleRagUnavailable(RagUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }
}
