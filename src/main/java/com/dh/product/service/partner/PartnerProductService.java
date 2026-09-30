package com.dh.product.service.partner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductSubmission;
import com.dh.product.domain.SubmissionStatus;
import com.dh.product.dto.PartnerDtos.PartnerOptionAxis;
import com.dh.product.dto.PartnerDtos.PartnerOptionsRequest;
import com.dh.product.dto.PartnerDtos.PartnerVariantRequest;
import com.dh.product.dto.ProductDtos.CreateOptionRequest;
import com.dh.product.dto.ProductDtos.CreateOptionValueRequest;
import com.dh.product.dto.ProductDtos.CreateVariantRequest;
import com.dh.product.dto.ProductDtos.OptionResponse;
import com.dh.product.dto.ProductDtos.UpdateVariantRequest;
import com.dh.product.dto.ProductDtos.VariantResponse;
import com.dh.product.dto.PartnerDtos.PartnerProductRequest;
import com.dh.product.dto.PartnerDtos.PartnerProductSummary;
import com.dh.product.dto.ProductDtos.ProductCreateRequest;
import com.dh.product.dto.ProductDtos.ProductResponse;
import com.dh.product.dto.ProductDtos.ProductSummaryResponse;
import com.dh.product.dto.ProductDtos.ProductUpdateRequest;
import com.dh.product.dto.SubmissionDtos.ProductAttributeResponse;
import com.dh.product.dto.SubmissionDtos.ProductAttributeValue;
import com.dh.product.dto.SubmissionDtos.SubmissionIssueResponse;
import com.dh.product.dto.SubmissionDtos.SubmissionResponse;
import com.dh.product.dto.PolicyDtos.ProductPolicyRequest;
import com.dh.product.dto.PolicyDtos.ProductPolicyResponse;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.ProductSubmissionRepository;
import com.dh.product.service.ProductPolicyService;
import com.dh.product.service.ProductService;
import com.dh.product.service.submission.ProductAttributeService;
import com.dh.product.service.submission.ProductSubmissionService;

/**
 * 파트너(외부 판매자) 상품 등록 흐름(product.api#75).
 *
 * <p>모든 메서드가 {@code sellerId} 를 받는다 - 호출부(PartnerController)가 검증된 토큰에서 꺼낸
 * 값이어야 한다. 남의 상품은 "없는 상품"과 똑같이 404 다(캐논 §보안 - 403 은 존재를 드러낸다).
 *
 * <p><b>수정 가능 조건</b>: 상품이 DRAFT 이고, 검수가 진행 중이 아닐 때(제출 이력 없음 또는 NEEDS_FIX).
 * <ul>
 *   <li>검수 중(SUBMITTED/VALIDATING/IN_REVIEW)에 고치면 심사자가 본 내용과 승인되는 내용이 달라진다.</li>
 *   <li>LIVE 상품을 바로 고치면 검수를 거치지 않고 판매 화면이 바뀐다. 판매 중 수정의 재검수 흐름은
 *       아직 없으므로(데이터 모델에 "변경 제안" 자리가 없다) 지금은 막는다.</li>
 * </ul>
 *
 * <p>쓰기 메서드는 판정과 쓰기를 <b>한 트랜잭션 + 상품 행 잠금</b> 안에서 한다. 판정만 따로 커밋하면
 * 제출을 연달아 누를 때 제출이 2건 생기거나, 제출 직후 수정이 끼어들어 심사자가 본 것과 다른 내용이
 * 승인될 수 있다(리뷰 지적). DB 에도 상품당 진행 중 제출 1건 부분 유니크 인덱스(V19)를 둔다.
 * 하위 서비스(ProductService 등)의 {@code @Transactional} 은 이 트랜잭션에 합류한다.
 *
 * <p>검증 실행 의뢰(publish)는 여기서 하지 않는다 - 호출부가 이 트랜잭션이 커밋된 뒤에 한다.
 */
@Service
public class PartnerProductService {

    /** 옵션 한도(product.api#80). 대형몰 등록 화면의 일반적 한도 수준 — 조합 폭발로 SKU 수천 개가 생기는 것을 막는다. */
    static final int MAX_OPTION_AXES = 3;
    static final int MAX_COMBINATIONS = 100;

    /** 검수가 진행 중이라 상품을 고치거나 다시 제출하면 안 되는 상태. */
    private static final Set<SubmissionStatus> IN_PROGRESS =
            EnumSet.of(SubmissionStatus.SUBMITTED, SubmissionStatus.VALIDATING, SubmissionStatus.IN_REVIEW);

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ProductSubmissionRepository submissionRepository;
    private final ProductSubmissionService submissionService;
    private final ProductAttributeService attributeService;
    private final ProductPolicyService policyService;

    public PartnerProductService(
            ProductService productService,
            ProductRepository productRepository,
            ProductSubmissionRepository submissionRepository,
            ProductSubmissionService submissionService,
            ProductAttributeService attributeService,
            ProductPolicyService policyService) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.submissionRepository = submissionRepository;
        this.submissionService = submissionService;
        this.attributeService = attributeService;
        this.policyService = policyService;
    }

    public List<PartnerProductSummary> list(Long sellerId) {
        List<ProductSummaryResponse> summaries = productService.listSellerProducts(sellerId);
        Map<Long, Product> byId = productRepository.findAllById(
                        summaries.stream().map(ProductSummaryResponse::id).toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        // 상품마다 제출 이력을 따로 조회하던 N+1 을 한 번의 조회로(product.api#78). 상품별 최신 = id 최대.
        Map<Long, ProductSubmission> latestByProduct = submissionRepository.findByProductIdIn(byId.keySet()).stream()
                .collect(Collectors.toMap(sub -> sub.getProduct().getId(), Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b));
        return summaries.stream()
                .map(s -> {
                    ProductSubmission latest = latestByProduct.get(s.id());
                    return new PartnerProductSummary(
                            s.id(), s.categoryId(), s.name(), s.price(), s.stockQuantity(), s.thumbnailUrl(),
                            byId.get(s.id()).getStatus().name(),
                            latest != null ? latest.getId() : null,
                            latest != null ? latest.getStatus().name() : null,
                            latest != null ? latest.getUpdatedAt() : null);
                })
                .toList();
    }

    public ProductResponse get(Long sellerId, Long productId) {
        ownedOrThrow(sellerId, productId);
        return productService.getProduct(productId);
    }

    /** 새 상품은 항상 DRAFT 로 만든다 - 검수 승인(ProductSubmissionService.approve)만이 LIVE 로 올린다. */
    public ProductResponse create(Long sellerId, PartnerProductRequest request) {
        return productService.createProduct(new ProductCreateRequest(
                request.categoryId(), request.name(), request.description(), request.price(),
                request.stockQuantity(), request.imageUrls(), request.listPrice(),
                null, null, null, request.freeShipping(), request.brand(),
                sellerId, ProductStatus.DRAFT.name()));
    }

    @Transactional
    public ProductResponse update(Long sellerId, Long productId, PartnerProductRequest request) {
        requireEditable(lockOwnedOrThrow(sellerId, productId));
        // 무료배송 표시는 판매 정책이 있으면 ProductService.updateProduct 가 거기서 파생한다(admin.front#56).
        // 판매자·상태는 null(=기존 유지). 평점·리뷰수·배송배지도 null - 파트너 상품에는 원래 값이 없다.
        return productService.updateProduct(productId, new ProductUpdateRequest(
                request.categoryId(), request.name(), request.description(), request.price(),
                request.stockQuantity(), request.imageUrls(), request.listPrice(),
                null, null, null, request.freeShipping(), request.brand(),
                null, null));
    }

    public List<ProductAttributeResponse> listAttributes(Long sellerId, Long productId) {
        ownedOrThrow(sellerId, productId);
        return attributeService.listAttributes(productId);
    }

    @Transactional
    public List<ProductAttributeResponse> replaceAttributes(
            Long sellerId, Long productId, List<ProductAttributeValue> values) {
        requireEditable(lockOwnedOrThrow(sellerId, productId));
        return attributeService.replaceAttributes(productId, values);
    }

    /**
     * 검수 제출. 처음이면 새 제출을, 보완 요청(NEEDS_FIX) 상태면 그 제출을 재제출한다 -
     * 파트너 화면은 "제출" 버튼 하나만 알면 된다. 반환값은 제출 id, 검증 실행 의뢰는 호출부가
     * 커밋 뒤에 한다(SubmissionController 와 같은 이유).
     */
    @Transactional
    public Long submit(Long sellerId, Long productId, String submittedBy) {
        Product product = lockOwnedOrThrow(sellerId, productId);
        requireEditable(product);
        ProductSubmission latest = latestSubmission(productId);
        if (latest != null && latest.getStatus() == SubmissionStatus.NEEDS_FIX) {
            submissionService.resubmit(latest.getId(), submittedBy);
            return latest.getId();
        }
        return submissionService.submit(productId, submittedBy);
    }

    /**
     * 가장 최근 검수 결과 + 입력칸별 이슈. 제출 이력이 없으면 404.
     *
     * <p>응답을 트랜잭션 안에서 만든다 - open-in-view 가 꺼져 있어 제출의 상품·판매자(LAZY)를
     * 컨트롤러에서 건드리면 LazyInitializationException 이 난다(리뷰 지적).
     *
     * <p>심사자 이메일(reviewedBy)은 내보내지 않고, 제출자도 요청한 본인이 아니면(직원이 대신 재제출한
     * 경우) 가린다 - 외부 판매자에게 직원 계정을 알려 줄 이유가 없다.
     */
    @Transactional(readOnly = true)
    public SubmissionResponse latestSubmission(Long sellerId, Long productId, String callerEmail) {
        ownedOrThrow(sellerId, productId);
        ProductSubmission s = latestSubmission(productId);
        if (s == null) {
            throw new NoSuchElementException("no submission for product: " + productId);
        }
        List<SubmissionIssueResponse> issues = submissionService.issuesOf(s.getId()).stream()
                .map(i -> new SubmissionIssueResponse(
                        i.getId(), i.getCode(), i.getField(), i.getMessage(), i.getSeverity().name()))
                .toList();
        String submittedBy = s.getSubmittedBy() != null && s.getSubmittedBy().equals(callerEmail)
                ? s.getSubmittedBy()
                : null;
        return new SubmissionResponse(
                s.getId(), s.getProduct().getId(), s.getProduct().getName(),
                s.getSeller().getId(), s.getSeller().getName(),
                s.getStatus().name(), submittedBy, null, s.getReviewNote(),
                s.getCreatedAt(), s.getUpdatedAt(), issues);
    }

    public ProductPolicyResponse getPolicy(Long sellerId, Long productId) {
        ownedOrThrow(sellerId, productId);
        return policyService.get(productId);
    }

    /** 판매 정책 전체 교체(product.api#79). 상품 수정과 같은 수정 가능 조건 + 행 잠금. */
    @Transactional
    public ProductPolicyResponse replacePolicy(Long sellerId, Long productId, ProductPolicyRequest request) {
        requireEditable(lockOwnedOrThrow(sellerId, productId));
        return policyService.replace(productId, request);
    }

    /**
     * 옵션 구성(product.api#80) — 축들의 모든 조합마다 SKU 를 만든다. 옵션 없는 기본 SKU 는
     * ProductService.createVariant 가 비활성화한다(product.api#47 규칙).
     *
     * <p>이미 옵션이 있는 상품은 거부한다(409). 재구성하려면 기존 SKU 를 지워야 하는데 SKU 삭제는
     * 재고 이력까지 지운다 — 파트너 셀프서비스로 열기 전에 따로 판단할 일이다.
     */
    @Transactional
    public ProductResponse configureOptions(Long sellerId, Long productId, PartnerOptionsRequest request) {
        Product product = lockOwnedOrThrow(sellerId, productId);
        requireEditable(product);
        if (!product.getOptions().isEmpty()) {
            throw new IllegalStateException("이미 옵션이 구성된 상품입니다. 옵션 재구성은 아직 지원하지 않습니다.");
        }
        List<List<String>> axes = validateAxes(request.options());

        List<List<Long>> valueIdsPerAxis = new ArrayList<>();
        for (int i = 0; i < axes.size(); i++) {
            OptionResponse option = productService.createOption(
                    productId, new CreateOptionRequest(request.options().get(i).name().trim()));
            List<Long> ids = new ArrayList<>();
            for (String value : axes.get(i)) {
                ids.add(productService.addOptionValue(productId, option.id(), new CreateOptionValueRequest(value)).id());
            }
            valueIdsPerAxis.add(ids);
        }
        for (List<Long> combination : cartesian(valueIdsPerAxis)) {
            productService.createVariant(productId, new CreateVariantRequest(
                    null, request.price(), request.stockQuantity(), combination));
        }
        return productService.getProduct(productId);
    }

    /** SKU 한 개의 가격·재고·판매 여부. 가격은 오퍼에도 반영된다(product.api#77). */
    @Transactional
    public VariantResponse updateVariant(Long sellerId, Long productId, Long variantId, PartnerVariantRequest request) {
        requireEditable(lockOwnedOrThrow(sellerId, productId));
        return productService.updateVariant(productId, variantId, new UpdateVariantRequest(
                request.sku(), request.price(), request.stockQuantity(), request.active()));
    }

    /** 축 수·값 공백/중복·조합 수 검사. 통과하면 다듬은 값 목록을 돌려준다. */
    static List<List<String>> validateAxes(List<PartnerOptionAxis> options) {
        if (options.isEmpty() || options.size() > MAX_OPTION_AXES) {
            throw new InvalidPartnerRequestException("옵션 종류는 1~" + MAX_OPTION_AXES + "개여야 합니다.");
        }
        Set<String> names = new HashSet<>();
        List<List<String>> result = new ArrayList<>();
        long combinations = 1;
        for (PartnerOptionAxis axis : options) {
            String name = axis.name() == null ? "" : axis.name().trim();
            if (name.isEmpty() || !names.add(name)) {
                throw new InvalidPartnerRequestException("옵션 이름이 비었거나 중복됩니다: " + name);
            }
            List<String> values = axis.values().stream().map(v -> v == null ? "" : v.trim()).toList();
            if (values.isEmpty() || values.stream().anyMatch(String::isEmpty)
                    || new HashSet<>(values).size() != values.size()) {
                throw new InvalidPartnerRequestException("'" + name + "' 옵션 값이 비었거나 중복됩니다.");
            }
            combinations *= values.size();
            if (combinations > MAX_COMBINATIONS) {
                throw new InvalidPartnerRequestException("옵션 조합은 " + MAX_COMBINATIONS + "개를 넘을 수 없습니다.");
            }
            result.add(values);
        }
        return result;
    }

    private static List<List<Long>> cartesian(List<List<Long>> axes) {
        List<List<Long>> acc = new ArrayList<>();
        acc.add(List.of());
        for (List<Long> axis : axes) {
            List<List<Long>> next = new ArrayList<>();
            for (List<Long> prefix : acc) {
                for (Long id : axis) {
                    List<Long> combo = new ArrayList<>(prefix);
                    combo.add(id);
                    next.add(combo);
                }
            }
            acc = next;
        }
        return acc;
    }

    private Product ownedOrThrow(Long sellerId, Long productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getSeller().getId().equals(sellerId))
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));
    }

    private Product lockOwnedOrThrow(Long sellerId, Long productId) {
        return productRepository.findByIdForUpdate(productId)
                .filter(p -> p.getSeller().getId().equals(sellerId))
                .orElseThrow(() -> new NoSuchElementException("product not found: " + productId));
    }

    private ProductSubmission latestSubmission(Long productId) {
        List<ProductSubmission> all = submissionRepository.findByProductIdOrderByIdDesc(productId);
        return all.isEmpty() ? null : all.get(0);
    }

    /** IllegalStateException → ApiExceptionHandler 가 409 로 응답한다. */
    private void requireEditable(Product product) {
        if (product.getStatus() != ProductStatus.DRAFT) {
            throw new IllegalStateException(
                    "판매 중이거나 판매 중지된 상품은 수정·제출할 수 없습니다 (상태: " + product.getStatus() + ")");
        }
        ProductSubmission latest = latestSubmission(product.getId());
        if (latest != null && IN_PROGRESS.contains(latest.getStatus())) {
            throw new IllegalStateException("검수가 진행 중인 상품은 수정·제출할 수 없습니다 (검수 상태: "
                    + latest.getStatus() + ")");
        }
    }
}
