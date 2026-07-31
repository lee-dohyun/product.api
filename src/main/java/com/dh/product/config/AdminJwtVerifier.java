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

// 게이트웨이의 JwtAuthenticationFilter와 같은 방식(Nimbus JOSE + JWKS)으로 Keycloak "staff" realm의
// JWT를 직접 검증한다. admin.front가 발급받은 토큰을 그대로 Authorization 헤더로 전달하면 여기서 재검증 -
// 서비스별 공유 시크릿을 배포/로테이션할 필요가 없어서 admin 보호가 필요한 서비스가 늘어나도 그대로 재사용 가능.
@Component
public class AdminJwtVerifier {

    // Keycloak이 내부 클러스터 URL로 요청받아도 항상 공개 URL을 issuer로 찍는다 (admin.front에서도 동일하게 확인됨)
    private static final String EXPECTED_ISSUER = "https://keycloak.leedohyun.com/realms/staff";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, RSAKey> keyCache = new ConcurrentHashMap<>();
    private final String jwksUri;

    public AdminJwtVerifier(
            @Value("${admin.staff-realm-url:http://keycloak-service.keycloak.svc.cluster.local/realms/staff}")
            String staffRealmUrl) {
        this.jwksUri = staffRealmUrl + "/protocol/openid-connect/certs";
    }

    /** 유효하면 email 클레임을, 아니면 null을 반환한다. */
    public String verify(String bearerToken) {
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
            if (!EXPECTED_ISSUER.equals(claims.getIssuer())) {
                return null;
            }
            return claims.getStringClaim("email");
        } catch (Exception e) {
            return null;
        }
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
