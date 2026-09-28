package com.dh.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 파트너 포털 API 입출력(product.api#75).
 *
 * <p>관리자용 {@code ProductCreateRequest} 를 그대로 받지 않는다. 그 요청에는 판매자·노출상태·평점·
 * 리뷰 수·배송 배지처럼 <b>판매자가 스스로 정하면 안 되는 값</b>이 들어 있다 - 판매자는 토큰에서,
 * 상태는 항상 DRAFT, 평점·리뷰 수는 리뷰 집계의 몫이다.
 */
public class PartnerDtos {

    /**
     * 생성·수정 공용. <b>수정은 전체 교체(PUT)다</b> - 빠진 필드는 "유지"가 아니라 "비움"으로 처리된다
     * (ProductService.updateProduct 가 이미지 목록을 비우고 다시 채운다). 그래서 이미지 목록은 빈 배열이라도
     * 반드시 보내게 한다 - null 을 허용하면 실수로 빠뜨렸을 때 이미지가 조용히 사라진다.
     */
    public record PartnerProductRequest(
            @NotNull Long categoryId,
            @NotBlank @Size(max = 200) String name,
            String description,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @NotNull @Min(0) Integer stockQuantity,
            @NotNull List<String> imageUrls,
            @DecimalMin(value = "0", inclusive = true) BigDecimal listPrice,
            boolean freeShipping,
            @Size(max = 100) String brand) {
    }

    /** 내 상품 목록 한 줄. 상품 상태와 최근 검수 상태를 같이 준다 - 파트너가 "어디서 멈췄나"를 한눈에 보게. */
    public record PartnerProductSummary(
            Long id,
            Long categoryId,
            String name,
            BigDecimal price,
            Integer stockQuantity,
            String thumbnailUrl,
            String status,
            Long submissionId,
            String submissionStatus,
            LocalDateTime submissionUpdatedAt) {
    }
}
