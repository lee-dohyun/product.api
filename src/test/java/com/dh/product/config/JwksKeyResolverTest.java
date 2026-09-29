package com.dh.product.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * product.api#78 - 모르는 kid 가 올 때마다 JWKS 를 다시 받으면, 외부인이 임의 kid 를 박은 토큰으로
 * Keycloak 을 두드리게 할 수 있다. 재조회는 최소 간격을 둔다.
 */
class JwksKeyResolverTest {

    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-29T00:00:00Z"));

    private JwksKeyResolver resolver(JWKSet set) {
        return new JwksKeyResolver(() -> {
            fetches.incrementAndGet();
            return set;
        }, Duration.ofSeconds(30), now::get);
    }

    private static RSAKey key(String kid) throws Exception {
        return new RSAKeyGenerator(2048).keyID(kid).generate().toPublicJWK();
    }

    @Test
    void knownKidIsServedFromCacheWithoutRefetch() throws Exception {
        JwksKeyResolver r = resolver(new JWKSet(key("a")));

        assertThat(r.resolve("a")).isNotNull();
        assertThat(r.resolve("a")).isNotNull();
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void unknownKidsWithinCooldownDoNotRefetch() throws Exception {
        JwksKeyResolver r = resolver(new JWKSet(key("a")));
        r.resolve("a");

        for (int i = 0; i < 100; i++) {
            assertThat(r.resolve("forged-" + i)).isNull();
        }
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void unknownKidAfterCooldownRefetchesOnce() throws Exception {
        JwksKeyResolver r = resolver(new JWKSet(key("a")));
        r.resolve("a");

        now.set(now.get().plusSeconds(31));
        assertThat(r.resolve("rotated")).isNull();
        assertThat(r.resolve("rotated")).isNull();
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void refetchCachesEveryKeyInTheSet() throws Exception {
        JwksKeyResolver r = resolver(new JWKSet(java.util.List.of(key("a"), key("b"))));

        r.resolve("a");
        assertThat(r.resolve("b")).isNotNull();
        assertThat(fetches.get()).isEqualTo(1);
    }
}
