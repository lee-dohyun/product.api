package com.dh.product.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SingleFlightTest {

    private static final int CALLERS = 8;

    private final SingleFlight singleFlight = new SingleFlight();
    private final ExecutorService pool = Executors.newFixedThreadPool(CALLERS);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("같은 키의 동시 적재는 한 번만 실행되고 모두 같은 결과를 받는다")
    void 같은_키는_한_번만() throws Exception {
        AtomicInteger loads = new AtomicInteger();

        List<String> results = callConcurrently(() -> singleFlight.load("k", () -> {
            sleep(200);
            return "v" + loads.incrementAndGet();
        }));

        assertThat(loads).hasValue(1);
        assertThat(results).containsOnly("v1");
    }

    @Test
    @DisplayName("키가 다르면 서로 기다리지 않고 각자 적재한다")
    void 다른_키는_각자() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();

        List<String> results = callConcurrently(() -> {
            String key = "k" + sequence.incrementAndGet();
            return singleFlight.load(key, () -> {
                loads.incrementAndGet();
                sleep(100);
                return key;
            });
        });

        assertThat(loads).hasValue(CALLERS);
        assertThat(results).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("적재가 실패하면 기다리던 호출도 같은 예외를 받고, 다음 호출은 새로 적재한다")
    void 실패는_전파되고_남지_않는다() throws Exception {
        AtomicInteger loads = new AtomicInteger();

        List<Future<String>> calls = submitConcurrently(() -> singleFlight.load("k", () -> {
            loads.incrementAndGet();
            sleep(200);
            throw new IllegalStateException("db down");
        }));
        for (Future<String> call : calls) {
            assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("db down");
        }
        assertThat(loads).hasValue(1);

        assertThat(singleFlight.load("k", () -> "recovered")).isEqualTo("recovered");
    }

    private List<String> callConcurrently(java.util.concurrent.Callable<String> call) throws Exception {
        List<String> results = new ArrayList<>();
        for (Future<String> future : submitConcurrently(call)) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        return results;
    }

    private List<Future<String>> submitConcurrently(java.util.concurrent.Callable<String> call) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        return futures;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
