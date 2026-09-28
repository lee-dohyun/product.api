package com.dh.product.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Keycloak "partner" realm JWT 직접 검증(product.api#75). {@link AdminJwtVerifier} 와 같은 방식
 * (Nimbus JOSE + JWKS)이지만 realm 이 다르다 - staff 토큰은 여기서 통과하지 않고, partner 토큰은
 * 관리 API 에서 통과하지 않는다. issuer 검사가 그 경계다.
 *
 * <p>게이트웨이 주입 헤더를 신뢰하지 않는다 - 2026-08-12 X-User-Email 스푸핑 인시던트가 헤더
 * 신뢰의 실패 사례이고, 외부인에게 쓰기를 여는 경로라 위험이 한 단계 높다(gateway#214).
 */
@Component
public class PartnerJwtVerifier {

    static final String SELLER_ID_CLAIM = "seller_id";

    private final String expectedIssuer;
    private final String expectedClientId;
    private final String jwksUri;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, RSAKey> keyCache = new ConcurrentHashMap<>();

    public PartnerJwtVerifier(
            @Value("${partner.realm-url:http://keycloak-service.keycloak.svc.cluster.local/realms/partner}")
            String partnerRealmUrl,
            @Value("${partner.realm-issuer:https://keycloak.posselect.com/realms/partner}")
            String expectedIssuer,
            @Value("${partner.client-id:partner-front}")
            String expectedClientId) {
        this.jwksUri = partnerRealmUrl + "/protocol/openid-connect/certs";
        this.expectedIssuer = expectedIssuer;
        this.expectedClientId = expectedClientId;
    }

    /** 유효하고 {@code seller_id} 클레임이 있으면 신원을, 아니면 null 을 반환한다. */
    public PartnerPrincipal verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        try {
            SignedJWT signedJwt = SignedJWT.parse(bearerToken);
            RSAKey rsaKey = resolveKey(signedJwt.getHeader().getKeyID());
            if (rsaKey == null || !signedJwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
                return null;
            }
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                return null;
            }
            if (!expectedIssuer.equals(claims.getIssuer())) {
                return null;
            }
            // partner realm 안에 다른 클라이언트가 생겨도 그 토큰으로 판매자 API 를 부르지 못하게
            // 발급 클라이언트(azp)를 포털 하나로 고정한다.
            if (!expectedClientId.equals(claims.getStringClaim("azp"))) {
                return null;
            }
            Long sellerId = parseSellerId(claims.getClaim(SELLER_ID_CLAIM));
            if (sellerId == null) {
                return null;
            }
            return new PartnerPrincipal(claims.getSubject(), claims.getStringClaim("email"), sellerId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Keycloak 사용자 속성 매퍼는 기본적으로 문자열로 싣는다("1"). 매퍼의 JSON 타입을 long 으로
     * 바꾸면 숫자로 온다. 둘 다 받는다 - 설정 하나 차이로 전 파트너 로그인이 막히지 않게.
     */
    static Long parseSellerId(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private RSAKey resolveKey(String kid) throws Exception {
        RSAKey cached = keyCache.get(kid);
        if (cached != null) {
            return cached;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JWKSet jwkSet = JWKSet.parse(response.body());
        RSAKey key = (RSAKey) jwkSet.getKeyByKeyId(kid);
        if (key != null) {
            keyCache.put(kid, key);
        }
        return key;
    }
}
