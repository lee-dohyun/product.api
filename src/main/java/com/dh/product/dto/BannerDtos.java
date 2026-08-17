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
}
