package com.dh.product.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * Keycloak JWKS 공개키 조회 + 캐시(product.api#78). Admin/Customer/Partner 세 검증기가 같은 코드를
 * 복사해 쓰던 것을 모았다.
 *
 * <p><b>재조회 최소 간격</b>: 예전에는 캐시에 없는 kid 가 올 때마다 JWKS 를 HTTP 로 다시 받았다. 서명
 * 검증 전 단계라 누구나 임의 kid 를 박은 토큰으로 이 서비스가 Keycloak 을 반복 호출하게 만들 수 있었다
 * (특히 외부인이 쓰는 /api/partner/**). 이제 간격 안의 미지 kid 는 조회 없이 거부한다. 정상적인 키
 * 교체(rotation)는 간격이 지난 뒤 첫 요청에서 반영된다.
 */
public class JwksKeyResolver {

    static final Duration DEFAULT_MIN_REFETCH_INTERVAL = Duration.ofSeconds(30);

    private final Supplier<JWKSet> fetcher;
    private final Duration minRefetchInterval;
    private final Supplier<Instant> clock;
    private final Map<String, RSAKey> keyCache = new ConcurrentHashMap<>();
    private volatile Instant lastFetch = Instant.MIN;

    public JwksKeyResolver(Supplier<JWKSet> fetcher, Duration minRefetchInterval, Supplier<Instant> clock) {
        this.fetcher = fetcher;
        this.minRefetchInterval = minRefetchInterval;
        this.clock = clock;
    }

    /** 운영용: realm 의 JWKS URI 를 HTTP 로 조회한다. */
    public static JwksKeyResolver forUri(String jwksUri) {
        HttpClient httpClient = HttpClient.newHttpClient();
        return new JwksKeyResolver(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                return JWKSet.parse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body());
            } catch (Exception e) {
                throw new IllegalStateException("JWKS 조회 실패: " + jwksUri, e);
            }
        }, DEFAULT_MIN_REFETCH_INTERVAL, Instant::now);
    }

    /** kid 에 해당하는 공개키. 모르는 kid 이고 최근에 조회했으면 조회 없이 null. */
    public RSAKey resolve(String kid) {
        if (kid == null) {
            return null;
        }
        RSAKey cached = keyCache.get(kid);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = keyCache.get(kid);
            if (cached != null) {
                return cached;
            }
            Instant now = clock.get();
            if (lastFetch != Instant.MIN && now.isBefore(lastFetch.plus(minRefetchInterval))) {
                return null;
            }
            lastFetch = now;
            // 한 번 받을 때 세트 전체를 캐시한다 - 키가 여러 개인 realm 에서 kid 마다 다시 받지 않게.
            for (JWK jwk : fetcher.get().getKeys()) {
                if (jwk instanceof RSAKey rsa && rsa.getKeyID() != null) {
                    keyCache.put(rsa.getKeyID(), rsa);
                }
            }
            return keyCache.get(kid);
        }
    }
}
