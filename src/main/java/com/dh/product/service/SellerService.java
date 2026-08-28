package com.dh.product.service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerDocument;
import com.dh.product.domain.SellerStatus;
import com.dh.product.domain.SellerStatusHistory;
import com.dh.product.domain.SellerType;
import com.dh.product.dto.SellerDtos.SellerCreateRequest;
import com.dh.product.dto.SellerDtos.SellerDetailResponse;
import com.dh.product.dto.SellerDtos.SellerDocumentCreateRequest;
import com.dh.product.dto.SellerDtos.SellerDocumentResponse;
import com.dh.product.dto.SellerDtos.SellerResponse;
import com.dh.product.dto.SellerDtos.SellerStatusHistoryResponse;
import com.dh.product.dto.SellerDtos.SellerSummaryResponse;
import com.dh.product.dto.SellerDtos.SellerUpdateRequest;
import com.dh.product.repository.SellerDocumentRepository;
import com.dh.product.repository.SellerRepository;
import com.dh.product.repository.SellerStatusHistoryRepository;

/**
 * 입점 심사 상태머신(product.api#29). 전이 규칙:
 * <pre>
 * DRAFT → SUBMITTED → IN_REVIEW → ACTIVE ⇄ SUSPENDED → TERMINATED
 *             ↑            ↓
 *       (보완 재제출)   REJECTED
 * </pre>
 * {@code boolean approved} 하나로 만들지 않은 이유는 클래스 상단이 아니라
 * {@link SellerStatus} 주석에 있다 - 상태 자체의 의미이기 때문이다.
 */
@Service
@Transactional(readOnly = true)
public class SellerService {

    /** 상태 -> 그 상태에서 전이 가능한 다음 상태 집합. 여기 없는 전이는 전부 거부된다. */
    private static final Map<SellerStatus, Set<SellerStatus>> VALID_TRANSITIONS = Map.of(
            SellerStatus.DRAFT, Set.of(SellerStatus.SUBMITTED),
            SellerStatus.SUBMITTED, Set.of(SellerStatus.IN_REVIEW),
            SellerStatus.IN_REVIEW, Set.of(SellerStatus.ACTIVE, SellerStatus.REJECTED),
            SellerStatus.REJECTED, Set.of(SellerStatus.SUBMITTED),
            SellerStatus.ACTIVE, Set.of(SellerStatus.SUSPENDED),
            SellerStatus.SUSPENDED, Set.of(SellerStatus.ACTIVE, SellerStatus.TERMINATED),
            SellerStatus.TERMINATED, Set.of());

    private final SellerRepository sellerRepository;
    private final SellerDocumentRepository sellerDocumentRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;

    public SellerService(
            SellerRepository sellerRepository,
            SellerDocumentRepository sellerDocumentRepository,
            SellerStatusHistoryRepository sellerStatusHistoryRepository) {
        this.sellerRepository = sellerRepository;
        this.sellerDocumentRepository = sellerDocumentRepository;
        this.sellerStatusHistoryRepository = sellerStatusHistoryRepository;
    }

    public List<SellerSummaryResponse> listSellers(SellerStatus status) {
        List<Seller> sellers = status != null ? sellerRepository.findByStatus(status) : sellerRepository.findAll();
        return sellers.stream().map(this::toSummaryResponse).toList();
    }

    public SellerDetailResponse getSeller(Long id) {
        Seller seller = findOrThrow(id);
        List<SellerDocumentResponse> documents = sellerDocumentRepository.findBySellerIdOrderByIdAsc(id).stream()
                .map(this::toDocumentResponse)
                .toList();
        List<SellerStatusHistoryResponse> history = sellerStatusHistoryRepository.findBySellerIdOrderByIdDesc(id)
                .stream()
                .map(this::toHistoryResponse)
                .toList();
        return new SellerDetailResponse(toResponse(seller), documents, history);
    }

    @Transactional
    public SellerResponse createSeller(SellerCreateRequest request) {
        Seller seller = new Seller();
        seller.setType(SellerType.SUPPLIER);
        seller.setStatus(SellerStatus.DRAFT);
        applyEditableFields(seller, request.name(), request.businessRegistrationNo(), request.mailOrderSalesNo(),
                request.representativeName(), request.address(), request.phone(), request.email());
        Seller saved = sellerRepository.save(seller);
        return toResponse(saved);
    }

    @Transactional
    public SellerResponse updateSeller(Long id, SellerUpdateRequest request) {
        Seller seller = findOrThrow(id);
        applyEditableFields(seller, request.name(), request.businessRegistrationNo(), request.mailOrderSalesNo(),
                request.representativeName(), request.address(), request.phone(), request.email());
        seller.setSettlementBank(request.settlementBank());
        seller.setSettlementAccount(request.settlementAccount());
        seller.setShippingOriginAddress(request.shippingOriginAddress());
        seller.setReturnAddress(request.returnAddress());
        seller.setShippingFeePolicy(request.shippingFeePolicy());
        seller.setCsContact(request.csContact());
        return toResponse(seller);
    }

    /**
     * 상태 전이. {@code changedBy}는 호출부(SellerController)가 인증된 관리자 이메일로 채운다 -
     * 요청 본문으로 받으면 위조 가능하다(다른 관리자 이름으로 승인 기록을 남길 수 있다).
     */
    @Transactional
    public SellerResponse transition(Long id, SellerStatus toStatus, String reasonCode, String reasonNote,
            String changedBy) {
        Seller seller = findOrThrow(id);
        SellerStatus fromStatus = seller.getStatus();
        Set<SellerStatus> allowed = VALID_TRANSITIONS.getOrDefault(fromStatus, Set.of());
        if (!allowed.contains(toStatus)) {
            throw new IllegalStateException(
                    "허용되지 않는 전이: " + fromStatus + " -> " + toStatus + " (허용: " + allowed + ")");
        }
        seller.setStatus(toStatus);
        sellerStatusHistoryRepository.save(
                new SellerStatusHistory(seller, fromStatus, toStatus, reasonCode, reasonNote, changedBy));
        return toResponse(seller);
    }

    @Transactional
    public SellerDocumentResponse addDocument(Long sellerId, SellerDocumentCreateRequest request) {
        Seller seller = findOrThrow(sellerId);
        SellerDocument document = new SellerDocument(seller, request.docType(), request.fileKey(), request.issuedAt());
        sellerDocumentRepository.save(document);
        return toDocumentResponse(document);
    }

    private void applyEditableFields(Seller seller, String name, String businessRegistrationNo,
            String mailOrderSalesNo, String representativeName, String address, String phone, String email) {
        seller.setName(name);
        seller.setBusinessRegistrationNo(businessRegistrationNo);
        seller.setMailOrderSalesNo(mailOrderSalesNo);
        seller.setRepresentativeName(representativeName);
        seller.setAddress(address);
        seller.setPhone(phone);
        seller.setEmail(email);
    }

    private Seller findOrThrow(Long id) {
        return sellerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("seller not found: " + id));
    }

    private SellerResponse toResponse(Seller s) {
        return new SellerResponse(
                s.getId(), s.getName(), s.getBusinessRegistrationNo(), s.getMailOrderSalesNo(),
                s.getRepresentativeName(), s.getAddress(), s.getPhone(), s.getEmail(),
                s.getStatus().name(), s.getType().name(),
                s.getSettlementBank(), s.getSettlementAccount(), s.getShippingOriginAddress(),
                s.getReturnAddress(), s.getShippingFeePolicy(), s.getCsContact(),
                s.getCreatedAt(), s.getUpdatedAt());
    }

    private SellerSummaryResponse toSummaryResponse(Seller s) {
        return new SellerSummaryResponse(s.getId(), s.getName(), s.getStatus().name(), s.getType().name(), s.getCreatedAt());
    }

    private SellerDocumentResponse toDocumentResponse(SellerDocument d) {
        return new SellerDocumentResponse(
                d.getId(), d.getDocType(), d.getFileKey(), d.getIssuedAt(), d.getVerifiedAt(), d.getRejectReason(),
                d.getCreatedAt());
    }

    private SellerStatusHistoryResponse toHistoryResponse(SellerStatusHistory h) {
        return new SellerStatusHistoryResponse(
                h.getId(),
                h.getFromStatus() != null ? h.getFromStatus().name() : null,
                h.getToStatus().name(),
                h.getReasonCode(), h.getReasonNote(), h.getChangedBy(), h.getCreatedAt());
    }
}
