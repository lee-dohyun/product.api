package com.dh.product.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.dto.InventoryDtos.DeductRequest;
import com.dh.product.dto.InventoryDtos.InventoryBalanceResponse;
import com.dh.product.service.InventoryDeductionService;

import jakarta.validation.Valid;

// order.api가 결제 확정(payOrder) 시 클러스터 내부망으로만 호출한다 - 게이트웨이에 라우트가
// 없어 외부에서는 도달 불가능하므로 별도 인증을 두지 않았다(gateway/CLAUDE.md의 내부 서비스
// 신뢰 경계 관례와 동일).
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryDeductionService inventoryDeductionService;

    public InventoryController(InventoryDeductionService inventoryDeductionService) {
        this.inventoryDeductionService = inventoryDeductionService;
    }

    @PostMapping("/deduct")
    public ResponseEntity<List<InventoryBalanceResponse>> deduct(@Valid @RequestBody DeductRequest request) {
        return ResponseEntity.ok(inventoryDeductionService.deductForOrder(request.orderId(), request.items()));
    }

    @PostMapping("/restore")
    public ResponseEntity<List<InventoryBalanceResponse>> restore(@Valid @RequestBody com.dh.product.dto.InventoryDtos.RestoreRequest request) {
        return ResponseEntity.ok(inventoryDeductionService.restoreForOrder(request.orderId(), request.items()));
    }
}
