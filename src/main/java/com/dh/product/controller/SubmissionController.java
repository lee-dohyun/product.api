package com.dh.product.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.config.AdminAuthInterceptor;
import com.dh.product.config.AdminPrincipal;
import com.dh.product.domain.ProductSubmission;
import com.dh.product.domain.SubmissionStatus;
import com.dh.product.dto.SubmissionDtos.ReviewRequest;
import com.dh.product.dto.SubmissionDtos.SubmissionIssueResponse;
import com.dh.product.dto.SubmissionDtos.SubmissionResponse;
import com.dh.product.dto.SubmissionDtos.SubmissionSummaryResponse;
import com.dh.product.dto.SubmissionDtos.SubmitAcceptedResponse;
import com.dh.product.dto.SubmissionDtos.SubmitRequest;
import com.dh.product.service.submission.ProductSubmissionService;
import com.dh.product.service.submission.SubmissionValidationPublisher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 상품 검수 제출/심사 API(product.api#30).
 *
 * <p>제출은 <b>비동기 리소스</b>다 - {@code POST} 는 202 와 {@code submissionId} 만 돌려주고
 * 검증 결과(이슈 리포트)는 {@code GET /api/submissions/{id}} 로 조회한다. 동기 응답만으로는
 * "제출은 받았는데 다운스트림에서 걸렸다"를 표현할 수 없기 때문이다.
 *
 * <p>검증 실행 의뢰(publish)를 서비스가 아니라 여기서 하는 이유는
 * {@link ProductSubmissionService#submit} 주석에 있다 - 커밋 이후에 발행해야 한다.
 */
@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final ProductSubmissionService submissionService;
    private final SubmissionValidationPublisher validationPublisher;

    public SubmissionController(
            ProductSubmissionService submissionService, SubmissionValidationPublisher validationPublisher) {
        this.submissionService = submissionService;
        this.validationPublisher = validationPublisher;
    }

    @PostMapping
    public ResponseEntity<SubmitAcceptedResponse> submit(
            @Valid @RequestBody SubmitRequest request, HttpServletRequest httpRequest) {
        Long submissionId = submissionService.submit(request.productId(), adminEmail(httpRequest));
        validationPublisher.publish(submissionId);
        return ResponseEntity.accepted()
                .body(new SubmitAcceptedResponse(submissionId, submissionService.get(submissionId).getStatus().name()));
    }

    @GetMapping("/{id}")
    public SubmissionResponse get(@PathVariable Long id) {
        return toResponse(submissionService.get(id));
    }

    @GetMapping
    public List<SubmissionSummaryResponse> list(@RequestParam(required = false) String status) {
        SubmissionStatus filter = status != null ? SubmissionStatus.valueOf(status) : null;
        return submissionService.list(filter).stream()
                .map(s -> new SubmissionSummaryResponse(
                        s.getId(), s.getProduct().getId(), s.getProduct().getName(),
                        s.getStatus().name(), s.getUpdatedAt()))
                .toList();
    }

    @PostMapping("/{id}/approve")
    public SubmissionResponse approve(
            @PathVariable Long id, @RequestBody(required = false) ReviewRequest request,
            HttpServletRequest httpRequest) {
        submissionService.approve(id, adminEmail(httpRequest), request != null ? request.reviewNote() : null);
        return toResponse(submissionService.get(id));
    }

    @PostMapping("/{id}/request-fix")
    public SubmissionResponse requestFix(
            @PathVariable Long id, @RequestBody(required = false) ReviewRequest request,
            HttpServletRequest httpRequest) {
        submissionService.requestFix(id, adminEmail(httpRequest), request != null ? request.reviewNote() : null);
        return toResponse(submissionService.get(id));
    }

    @PostMapping("/{id}/resubmit")
    public ResponseEntity<SubmitAcceptedResponse> resubmit(@PathVariable Long id, HttpServletRequest httpRequest) {
        submissionService.resubmit(id, adminEmail(httpRequest));
        validationPublisher.publish(id);
        return ResponseEntity.accepted()
                .body(new SubmitAcceptedResponse(id, submissionService.get(id).getStatus().name()));
    }

    /** 인터셉터가 검증해 넣어둔 신원. 요청 본문으로 받으면 다른 관리자 이름으로 기록을 남길 수 있다. */
    private String adminEmail(HttpServletRequest request) {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute(AdminAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        return admin.email();
    }

    private SubmissionResponse toResponse(ProductSubmission s) {
        List<SubmissionIssueResponse> issues = submissionService.issuesOf(s.getId()).stream()
                .map(i -> new SubmissionIssueResponse(
                        i.getId(), i.getCode(), i.getField(), i.getMessage(), i.getSeverity().name()))
                .toList();
        return new SubmissionResponse(
                s.getId(), s.getProduct().getId(), s.getProduct().getName(),
                s.getSeller().getId(), s.getSeller().getName(),
                s.getStatus().name(), s.getSubmittedBy(), s.getReviewedBy(), s.getReviewNote(),
                s.getCreatedAt(), s.getUpdatedAt(), issues);
    }
}
