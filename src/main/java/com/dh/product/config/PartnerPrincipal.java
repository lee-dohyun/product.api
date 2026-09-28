package com.dh.product.config;

/**
 * partner realm JWT 에서 뽑아낸 외부 판매자 계정의 신원(product.api#75).
 *
 * <p>{@code sellerId} 가 이 계정이 볼 수 있는 데이터의 경계다. 요청 본문이나 게이트웨이 헤더에서
 * 받지 않고 서명 검증된 토큰의 {@code seller_id} 클레임에서만 읽는다(gateway Wiki Glossary §8-1).
 */
public record PartnerPrincipal(String subject, String email, Long sellerId) {
}
