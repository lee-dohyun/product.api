package com.dh.product.service.submission;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.IssueSeverity;
import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductSubmission;
import com.dh.product.domain.Seller;
import com.dh.product.domain.SubmissionIssue;
import com.dh.product.domain.SubmissionStatus;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductSubmissionRepository;
import com.dh.product.repository.SubmissionIssueRepository;

/**
 * 상품 검수 제출 상태머신(product.api#30).
 *
 * <p>전이 규칙은 {@link SubmissionStatus} 주석의 다이어그램과 같아야 한다.
 * 클래스 레벨 {@code readOnly = true} 이므로 <b>쓰기 메서드에는 반드시 {@code @Transactional}
 * 을 따로 붙인다</b> - 안 붙이면 UPDATE 가 조용히 사라진다(캐논 §3, posselect #211).
 */
@Service
@Transactional(readOnly = true)
public class ProductSubmissionService {

    /** 상태 -> 그 상태에서 전이 가능한 다음 상태. 여기 없는 전이는 전부 거부된다. */
    private static final Map<SubmissionStatus, Set<SubmissionStatus>> VALID_TRANSITIONS = Map.of(
            SubmissionStatus.DRAFT, Set.of(SubmissionStatus.SUBMITTED),
            SubmissionStatus.SUBMITTED, Set.of(SubmissionStatus.VALIDATING),
            SubmissionStatus.VALIDATING, Set.of(SubmissionStatus.IN_REVIEW, SubmissionStatus.NEEDS_FIX),
            SubmissionStatus.NEEDS_FIX, Set.of(SubmissionStatus.SUBMITTED),
            SubmissionStatus.IN_REVIEW, Set.of(SubmissionStatus.LIVE, SubmissionStatus.NEEDS_FIX),
            SubmissionStatus.LIVE, Set.of(SubmissionStatus.PAUSED),
            SubmissionStatus.PAUSED, Set.of(SubmissionStatus.LIVE));

    private final ProductSubmissionRepository submissionRepository;
    private final SubmissionIssueRepository issueRepository;
    private final ProductRepository productRepository;
    private final SubmissionValidator validator;

    public ProductSubmissionService(
            ProductSubmissionRepository submissionRepository,
            SubmissionIssueRepository issueRepository,
            ProductRepository productRepository,
            SubmissionValidator validator) {
        this.submissionRepository = submissionRepository;
        this.issueRepository = issueRepository;
        this.productRepository = productRepository;
        this.validator = validator;
    }

    /**
     * 제출 접수. <b>접수만 하고 검증은 여기서 돌리지 않는다</b> - 검증 실행 의뢰는 호출부
     * (컨트롤러)가 이 트랜잭션이 커밋된 뒤에 {@link SubmissionValidationPublisher} 로 한다.
     *
     * <p>이 클래스가 publisher 를 직접 들지 않는 이유는 두 가지다. (1) publisher 의 실행
     * 대상이 다시 이 서비스라 빈 순환 의존이 된다. (2) 큐로 바뀌면 발행은 커밋 이후여야
     * 한다 - 트랜잭션 안에서 발행하면 롤백된 제출에 대한 검증 작업이 큐에 남는다.
     */
    @Transactional
    public Long submit(Long productId, String submittedBy) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));
        Seller seller = product.getSeller();

        ProductSubmission submission =
                new ProductSubmission(product, seller, SubmissionStatus.SUBMITTED, submittedBy);
        submissionRepository.save(submission);
        return submission.getId();
    }

    /**
     * 규칙 검증 실행. SUBMITTED 에서만 진입하며 결과에 따라 IN_REVIEW 또는 NEEDS_FIX 로 간다.
     *
     * <p>이전 이슈는 지우고 새로 쓴다 - "지금 이 제출에 남아 있는 문제"가 조회의 목적이라
     * 재검증 때 과거 이슈가 섞이면 안 된다.
     */
    @Transactional
    public void runValidation(Long submissionId) {
        ProductSubmission submission = findOrThrow(submissionId);
        transitionTo(submission, SubmissionStatus.VALIDATING);

        issueRepository.deleteBySubmissionId(submissionId);
        List<ValidationFinding> findings = validator.validate(submission.getProduct(), submission.getSeller());
        findings.forEach(f -> issueRepository.save(
                new SubmissionIssue(submission, f.code(), f.field(), f.message(), f.severity())));

        boolean blocked = findings.stream().anyMatch(f -> f.severity() == IssueSeverity.BLOCKING);
        transitionTo(submission, blocked ? SubmissionStatus.NEEDS_FIX : SubmissionStatus.IN_REVIEW);
    }

    /** 심사자 승인 - 여기서만 상품이 실제로 노출된다(products.status = LIVE). */
    @Transactional
    public void approve(Long submissionId, String reviewedBy, String reviewNote) {
        ProductSubmission submission = findOrThrow(submissionId);
        transitionTo(submission, SubmissionStatus.LIVE);
        submission.setReviewedBy(reviewedBy);
        submission.setReviewNote(reviewNote);
        submission.getProduct().setStatus(ProductStatus.LIVE);
    }

    /** 심사자 보완 요청. 규칙이 못 잡는 사유를 사람이 남긴다. */
    @Transactional
    public void requestFix(Long submissionId, String reviewedBy, String reviewNote) {
        ProductSubmission submission = findOrThrow(submissionId);
        transitionTo(submission, SubmissionStatus.NEEDS_FIX);
        submission.setReviewedBy(reviewedBy);
        submission.setReviewNote(reviewNote);
        issueRepository.save(new SubmissionIssue(submission, "REVIEWER_REQUESTED_FIX", null,
                reviewNote != null ? reviewNote : "심사자 보완 요청", IssueSeverity.BLOCKING));
    }

    /** 보완 후 재제출. NEEDS_FIX 에서만 가능하다 - 검증 실행 의뢰는 {@link #submit} 과 같이 호출부가 한다. */
    @Transactional
    public void resubmit(Long submissionId, String submittedBy) {
        ProductSubmission submission = findOrThrow(submissionId);
        transitionTo(submission, SubmissionStatus.SUBMITTED);
        submission.setSubmittedBy(submittedBy);
    }

    public ProductSubmission get(Long submissionId) {
        return findOrThrow(submissionId);
    }

    public List<SubmissionIssue> issuesOf(Long submissionId) {
        return issueRepository.findBySubmissionIdOrderByIdAsc(submissionId);
    }

    public List<ProductSubmission> list(SubmissionStatus status) {
        return status != null
                ? submissionRepository.findByStatusOrderByIdDesc(status)
                : submissionRepository.findAll();
    }

    private void transitionTo(ProductSubmission submission, SubmissionStatus toStatus) {
        SubmissionStatus fromStatus = submission.getStatus();
        Set<SubmissionStatus> allowed = VALID_TRANSITIONS.getOrDefault(fromStatus, Set.of());
        if (!allowed.contains(toStatus)) {
            throw new IllegalStateException(
                    "허용되지 않는 전이: " + fromStatus + " -> " + toStatus + " (허용: " + allowed + ")");
        }
        submission.setStatus(toStatus);
    }

    private ProductSubmission findOrThrow(Long submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new NoSuchElementException("submission not found: " + submissionId));
    }
}
