package com.dh.product.dto;

/**
 * 메인 페이지 배너 노출을 위한 데이터 전송 객체 모음
 */
public class BannerDtos {

    /**
     * 클라이언트(프론트엔드)에 전달할 배너 정보
     */
    public record BannerResponse(
            Long id,
            String title,
            String subtitle,
            String imageUrl,
            String link,
            String bgColor
    ) {}

    /**
     * 관리자용 배너 응답 정보 (순서, 활성 상태 포함)
     */
    public record BannerAdminResponse(
            Long id,
            String title,
            String subtitle,
            String imageUrl,
            String link,
            String bgColor,
            Integer sortOrder,
            Boolean isActive
    ) {}

    public record BannerCreateRequest(
            String title,
            String subtitle,
            String imageUrl,
            String link,
            String bgColor,
            Integer sortOrder,
            Boolean isActive
    ) {}

    public record BannerUpdateRequest(
            String title,
            String subtitle,
            String imageUrl,
            String link,
            String bgColor,
            Integer sortOrder,
            Boolean isActive
    ) {}
}
