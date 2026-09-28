package com.dh.product.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.dh.product.domain.Seller;
import com.dh.product.domain.SellerStatus;
import com.dh.product.repository.SellerRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /api/partner/**} 인증(product.api#75).
 *
 * <p>{@link AdminAuthInterceptor} 와 달리 <b>GET 도 인증한다</b> - 파트너 API 가 돌려주는 것은
 * 그 판매자의 비공개 상품(임시저장·검수 중)이라 공개할 이유가 없다.
 *
 * <p>판매자가 {@code ACTIVE} 가 아니면(심사 중·정지·해지) 쓰기를 막는다. 조회는 허용한다 -
 * 정지된 판매자도 자기 상품과 반려 사유는 볼 수 있어야 한다.
 */
@Component
public class PartnerAuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(PartnerAuthInterceptor.class);

    public static final String PRINCIPAL_ATTRIBUTE = "partnerPrincipal";

    private final PartnerJwtVerifier verifier;
    private final SellerRepository sellerRepository;

    public PartnerAuthInterceptor(PartnerJwtVerifier verifier, SellerRepository sellerRepository) {
        this.verifier = verifier;
        this.sellerRepository = sellerRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String authHeader = request.getHeader("Authorization");
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
        PartnerPrincipal partner = verifier.verify(token);
        if (partner == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "partner login required");
            return false;
        }
        Seller seller = sellerRepository.findById(partner.sellerId()).orElse(null);
        if (seller == null) {
            // 토큰은 유효한데 판매자가 없다 - 계정 발급 설정 오류다. 조용히 넘기지 않는다.
            logger.warn("partner 토큰의 seller_id 에 해당하는 판매자 없음: sellerId={} sub={}",
                    partner.sellerId(), partner.subject());
            response.sendError(HttpStatus.FORBIDDEN.value(), "seller not found");
            return false;
        }
        boolean write = !"GET".equalsIgnoreCase(request.getMethod());
        if (write && seller.getStatus() != SellerStatus.ACTIVE) {
            logger.warn("partner write 거부(판매자 상태 {}): {} {} sellerId={}",
                    seller.getStatus(), request.getMethod(), request.getRequestURI(), seller.getId());
            response.sendError(HttpStatus.FORBIDDEN.value(), "seller not active");
            return false;
        }
        if (write) {
            logger.info("partner write: {} {} sellerId={} by {}",
                    request.getMethod(), request.getRequestURI(), seller.getId(), partner.email());
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, partner);
        return true;
    }
}
