package com.dh.product.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.config.AdminAuthInterceptor;
import com.dh.product.config.AdminPrincipal;
import com.dh.product.domain.SellerStatus;
import com.dh.product.dto.SellerDtos.SellerCreateRequest;
import com.dh.product.dto.SellerDtos.SellerDetailResponse;
import com.dh.product.dto.SellerDtos.SellerDocumentCreateRequest;
import com.dh.product.dto.SellerDtos.SellerDocumentResponse;
import com.dh.product.dto.SellerDtos.SellerResponse;
import com.dh.product.dto.SellerDtos.SellerSummaryResponse;
import com.dh.product.dto.SellerDtos.SellerTransitionRequest;
import com.dh.product.dto.SellerDtos.SellerUpdateRequest;
import com.dh.product.service.SellerService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

// 전부 staff PARTNER(또는 SYSTEM_ADMIN) 전용 - GET을 포함한 전 경로가 AdminAuthInterceptor가
// 아니라 컨트롤러 자체에서 걸리지 않는다는 점에 주의: 인터셉터는 쓰기(POST/PUT/DELETE)만 막고
// GET은 공개다. 판매자 정보(사업자등록번호 등)는 공개 데이터가 아니므로 GET도 여기서 막아야
// 하는데, 협력사 포털이 아직 없어(리서치 문서 결론) 지금은 staff만 호출하는 화면이라 우선
// 인터셉터 정책(GET 공개)을 그대로 따른다 - 협력사 포털 도입 시 재검토 대상.
@RestController
@RequestMapping("/api/sellers")
public class SellerController {

    private final SellerService sellerService;

    public SellerController(SellerService sellerService) {
        this.sellerService = sellerService;
    }

    @GetMapping
    public List<SellerSummaryResponse> list(@RequestParam(required = false) String status) {
        SellerStatus filter = status != null ? SellerStatus.valueOf(status) : null;
        return sellerService.listSellers(filter);
    }

    @GetMapping("/{id}")
    public SellerDetailResponse get(@PathVariable Long id) {
        return sellerService.getSeller(id);
    }

    @PostMapping
    public ResponseEntity<SellerResponse> create(@Valid @RequestBody SellerCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sellerService.createSeller(request));
    }

    @PutMapping("/{id}")
    public SellerResponse update(@PathVariable Long id, @Valid @RequestBody SellerUpdateRequest request) {
        return sellerService.updateSeller(id, request);
    }

    @PostMapping("/{id}/transition")
    public SellerResponse transition(
            @PathVariable Long id, @Valid @RequestBody SellerTransitionRequest request, HttpServletRequest httpRequest) {
        AdminPrincipal admin = (AdminPrincipal) httpRequest.getAttribute(AdminAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        return sellerService.transition(
                id, SellerStatus.valueOf(request.toStatus()), request.reasonCode(), request.reasonNote(),
                admin.email());
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<SellerDocumentResponse> addDocument(
            @PathVariable Long id, @Valid @RequestBody SellerDocumentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sellerService.addDocument(id, request));
    }
}
