package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dh.product.domain.Product;
import com.dh.product.domain.ProductStatus;
import com.dh.product.domain.ProductVariant;
import com.dh.product.domain.Seller;
import com.dh.product.repository.ProductVariantRepository;

/**
 * 비로그인 장바구니 -> 계정 장바구니 병합(product.api#143)을 진짜 Redis 에서 검증한다.
 *
 * <p>이중 합산 방지는 GETDEL 의 원자성에 기대므로 목 기반 단위 테스트로는 증명이 성립하지 않는다(캐논 §3).
 * 커밋된 Redis 값을 직접 읽어 확인한다. 운영과 같은 메이저 버전(7.x)을 쓴다 - GETDEL 은 6.2 부터 있다.
 */
@Testcontainers
class CartMergeRedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final ProductVariantRepository variants = mock(ProductVariantRepository.class);
    private final PurchaseRules purchaseRules = mock(PurchaseRules.class);
    private CartService cartService;

    private final ProductVariant shirtRed = variant(1L, 10L, ProductStatus.LIVE);
    private final ProductVariant shirtBlue = variant(2L, 10L, ProductStatus.LIVE);
    private final ProductVariant mug = variant(3L, 20L, ProductStatus.LIVE);
    private final ProductVariant hidden = variant(4L, 30L, ProductStatus.DRAFT);

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        Map<Long, ProductVariant> byId = Map.of(1L, shirtRed, 2L, shirtBlue, 3L, mug, 4L, hidden);
        when(variants.findAllById(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(byId::get).filter(v -> v != null).toList();
        });
        cartService = new CartService(redis, variants, purchaseRules);
    }

    @Test
    void sumsSameSkuMovesNewSkuAndDeletesGuestCart() {
        String guest = UUID.randomUUID().toString();
        String user = CartService.userCartId("sub-1");
        put(guest, "{\"1\":2,\"3\":1}");
        put(user, "{\"1\":1}");

        int merged = cartService.mergeGuestCart(guest, user);

        assertThat(merged).isEqualTo(2);
        assertThat(get(user)).isEqualTo("{\"1\":3,\"3\":1}");
        assertThat(redis.hasKey("cart:" + guest)).isFalse();
        assertThat(redis.getExpire("cart:" + user)).isPositive();
    }

    @Test
    void concurrentMergesOfSameGuestCartAddOnlyOnce() throws Exception {
        String guest = UUID.randomUUID().toString();
        String user = CartService.userCartId("sub-2");
        put(guest, "{\"3\":2}");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> task = () -> {
                start.await();
                return cartService.mergeGuestCart(guest, user);
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int totalMerged = 0;
        for (Future<Integer> r : results) {
            totalMerged += r.get();
        }
        pool.shutdown();

        assertThat(totalMerged).isEqualTo(1);
        assertThat(get(user)).isEqualTo("{\"3\":2}");
    }

    @Test
    void keepsAccountQuantityWhenSumBreaksPurchaseRule() {
        String guest = UUID.randomUUID().toString();
        String user = CartService.userCartId("sub-3");
        put(guest, "{\"2\":2,\"3\":1}");
        put(user, "{\"1\":2}");
        // 상품 10 은 1회 최대 3개 - 계정 2 + 비로그인 2 = 4 라 위반
        doThrow(new PurchaseRuleViolationException("purchase.maxQuantityExceeded", 3))
                .when(purchaseRules).checkCart(eq(10L), anyInt());

        int merged = cartService.mergeGuestCart(guest, user);

        assertThat(merged).isEqualTo(1);
        assertThat(get(user)).isEqualTo("{\"1\":2,\"3\":1}");
    }

    @Test
    void movesGuestItemsAsIsWhenAccountHasNoneOfThatProduct() {
        String guest = UUID.randomUUID().toString();
        String user = CartService.userCartId("sub-4");
        put(guest, "{\"1\":1}");
        doThrow(new PurchaseRuleViolationException("purchase.outOfSalePeriod"))
                .when(purchaseRules).checkCart(eq(10L), anyInt());

        assertThat(cartService.mergeGuestCart(guest, user)).isEqualTo(1);
        assertThat(get(user)).isEqualTo("{\"1\":1}");
    }

    @Test
    void dropsHiddenOrDeletedSkus() {
        String guest = UUID.randomUUID().toString();
        String user = CartService.userCartId("sub-5");
        put(guest, "{\"4\":1,\"999\":1}");

        assertThat(cartService.mergeGuestCart(guest, user)).isZero();
        assertThat(redis.hasKey("cart:" + user)).isFalse();
        assertThat(redis.hasKey("cart:" + guest)).isFalse();
    }

    @Test
    void emptyOrMissingGuestCartIsNoop() {
        String user = CartService.userCartId("sub-6");
        put(user, "{\"1\":1}");

        assertThat(cartService.mergeGuestCart(UUID.randomUUID().toString(), user)).isZero();
        assertThat(get(user)).isEqualTo("{\"1\":1}");
    }

    private static void put(String cartId, String json) {
        redis.opsForValue().set("cart:" + cartId, json);
    }

    private static String get(String cartId) {
        return redis.opsForValue().get("cart:" + cartId);
    }

    private static ProductVariant variant(long id, long productId, ProductStatus status) {
        Seller seller = new Seller();
        ReflectionTestUtils.setField(seller, "id", 1L);
        Product product = new Product();
        product.setStatus(status);
        product.setSeller(seller);
        ReflectionTestUtils.setField(product, "id", productId);
        ProductVariant v = new ProductVariant(product, "opt", BigDecimal.TEN);
        ReflectionTestUtils.setField(v, "id", id);
        return v;
    }
}
