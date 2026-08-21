package com.dh.product.controller;

import java.util.NoSuchElementException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
     * 주문 차감(InventoryDeductor#deductOnce)은 더 이상 낙관적 락을 쓰지 않지만(product.api#35,
     * InventoryRepository#deductQuantity의 원자적 UPDATE로 대체), 재고 초기화/관리자 수동 조정/
     * 주문 취소 시 재고 복원 경로는 여전히 Inventory의 {@code @Version}에 기대는 read-modify-write다.
     * 그 경로들이 동시 경합으로 {@link org.springframework.orm.ObjectOptimisticLockingFailureException}을
     * 던질 때도 raw 500 대신 이 매핑으로 깨끗한 409를 받게 한다
     * ({@code OptimisticLockingFailureException}이 그 상위 타입이라 하나로 함께 잡는다).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<String> handleOptimisticLockConflict(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("요청이 동시에 처리되어 재시도가 필요합니다.");
    }
}
