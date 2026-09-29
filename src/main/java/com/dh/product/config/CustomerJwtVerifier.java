package com.dh.product.config;

import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

// 게이트웨이가 검증한 토큰을 받더라도 한 번 더 직접 검증(Nimbus JOSE + JWKS)하여
// Keycloak "customer" realm의 JWT 서명을 확인한다. (X-User-Id 위조 방지 목적)
@Component
public class CustomerJwtVerifier {

    private final String expectedIssuer;
    private final JwksKeyResolver keys;

    public CustomerJwtVerifier(
            @Value("${shop.keycloak.customer-realm-url:http://keycloak-service.keycloak.svc.cluster.local/realms/customer}")
            String customerRealmUrl,
            @Value("${shop.keycloak.customer-realm-issuer:https://keycloak.posselect.com/realms/customer}")
            String expectedIssuer) {
        this.keys = JwksKeyResolver.forUri(customerRealmUrl + "/protocol/openid-connect/certs");
        this.expectedIssuer = expectedIssuer;
    }

    /** 유효하면 JWTClaimsSet을, 아니면 null을 반환한다. */
    public JWTClaimsSet verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return null;
        }
        
        if (bearerToken.startsWith("Bearer ")) {
            bearerToken = bearerToken.substring(7);
        }
        
        try {
            SignedJWT signedJwt = SignedJWT.parse(bearerToken);
            RSAKey rsaKey = keys.resolve(signedJwt.getHeader().getKeyID());
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
            return claims;
        } catch (Exception e) {
            return null;
        }
    }
}
