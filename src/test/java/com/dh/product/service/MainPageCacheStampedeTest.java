package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dh.product.config.CacheNames;
import com.dh.product.dto.BannerDtos.BannerResponse;
import com.dh.product.repository.BannerRepository;

/**
 * 메인 페이지 캐시의 스탬피드 방지 (posselect-shell#27).
 *
 * <p>캐시가 비는 순간(TTL 만료, 쓰기 뒤 무효화) 동시에 들어온 요청이 전부 DB 로 내려가면, 캐시가 막아 주던
 * 부하가 한꺼번에 몰린다. 메인 페이지 조회는 {@code SingleFlight} 로 묶여 한 요청만 DB 를 읽어야 한다.
 * 빠져도 아무 오류가 없는 배선이라, 실제 서비스 빈을 동시에 불러 조회 횟수를 센다.
 *
 * <p>테스트 캐시({@code ConcurrentMapCacheManager})는 운영(Redis)처럼 동시 미스를 전부 메서드 본문까지
 * 통과시킨다 — 그래서 여기서 한 번만 읽히면 캐시 구현이 아니라 SingleFlight 가 막은 것이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MainPageCacheStampedeTest {

    private static final int CONCURRENT_CALLERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withStartupTimeout(Duration.ofMinutes(3));

    @TestConfiguration
    static class LocalCacheConfig {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                    CacheNames.PRODUCT,
                    CacheNames.MAIN_BEST,
                    CacheNames.MAIN_NEW,
                    CacheNames.MAIN_BY_CATEGORY,
                    CacheNames.MAIN_BANNERS);
        }
    }

    @Autowired private MainPageService mainPageService;
    @MockitoBean private BannerRepository bannerRepository;

    @Test
    @DisplayName("캐시가 빈 상태에서 동시에 들어온 조회는 DB 를 한 번만 읽는다")
    void 동시_조회는_한_번만_적재한다() throws Exception {
        // 적재가 끝나기 전에 나머지 호출이 모두 도착하도록 조회를 느리게 만든다.
        when(bannerRepository.findAllByIsActiveTrueOrderBySortOrderAsc()).thenAnswer(invocation -> {
            Thread.sleep(300);
            return List.of();
        });

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<BannerResponse>>> calls = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CALLERS; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return mainPageService.getBanners();
                }));
            }
            start.countDown();
            for (Future<List<BannerResponse>> call : calls) {
                assertThat(call.get(10, TimeUnit.SECONDS)).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }

        verify(bannerRepository, times(1)).findAllByIsActiveTrueOrderBySortOrderAsc();
    }
}
