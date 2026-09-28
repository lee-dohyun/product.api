package com.dh.product.config;

/**
 * partner realm JWT 에서 뽑아낸 외부 판매자 계정의 신원(product.api#75).
 *
 * <p>{@code sellerId} 가 이 계정이 볼 수 있는 데이터의 경계다. 요청 본문이나 게이트웨이 헤더에서
 * 받지 않고 서명 검증된 토큰의 {@code seller_id} 클레임에서만 읽는다(gateway Wiki Glossary §8-1).
 */
public record PartnerPrincipal(String subject, String email, Long sellerId) {

    /**
     * 감사 기록(검수 제출자 등)에 남길 행위자 식별값. 이메일이 있으면 이메일, 없으면 {@code partner:<sub>}.
     *
     * <p>파트너 계정은 관리자가 발급하므로 이메일 없이 만들어질 수 있다. email 을 그대로 쓰면
     * {@code product_submissions.submitted_by}(NOT NULL)에서 제출이 500 으로 죽는다(product.api#86, 운영 실측).
     */
    public String actor() {
        return email != null && !email.isBlank() ? email : "partner:" + subject;
    }
}
