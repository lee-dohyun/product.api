package com.dh.product.config;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * 같은 키의 적재가 동시에 여러 번 들어오면 한 번만 실행하고 나머지는 그 결과를 같이 받는다
 * (캐시 스탬피드 방지, posselect-shell#27).
 *
 * <p>캐시가 비는 순간(TTL 만료, 쓰기 뒤 무효화) 동시에 들어온 요청은 전부 캐시 미스를 보고 DB 로 내려간다.
 * {@code @Cacheable} 메서드 본문을 이걸로 감싸면 그중 하나만 DB 를 읽는다.
 *
 * <p><b>{@code @Cacheable(sync = true)} 를 쓰지 않는 이유.</b> Redis 캐시에서 그 옵션은 기본(비잠금) writer 로는
 * 아무 효과가 없고, 잠금 writer 로 바꾸면 캐시 <i>히트</i>까지 매번 Redis 잠금을 잡았다 놓는다
 * (spring-data-redis 3.5 {@code DefaultRedisCacheWriter.get}). 히트 경로는 그대로 두고 미스 경로만 묶는다.
 *
 * <p>범위는 이 JVM 하나다. 파드가 여러 개면 파드마다 한 번씩 읽는다 — 그래도 요청 수만큼 읽는 것은 막는다.
 * 앞선 적재가 끝난 직후 캐시에 값이 들어가기 전 사이에 도착한 요청은 한 번 더 적재할 수 있다.
 */
@Component
public class SingleFlight {

    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    /**
     * @param key 적재 대상을 구분하는 키. 결과가 달라지는 인자(예: limit)는 키에 포함해야 한다 —
     *            빠뜨리면 다른 인자로 부른 호출이 남의 결과를 받는다.
     */
    @SuppressWarnings("unchecked")
    public <T> T load(String key, Supplier<T> loader) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) {
            return (T) await(leader);
        }
        try {
            T value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            // 기다리던 호출도 같은 실패를 받는다 — 실패를 삼키고 각자 다시 읽게 하면 장애 때 스탬피드가 그대로 난다.
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private static Object await(CompletableFuture<Object> leader) {
        try {
            return leader.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
