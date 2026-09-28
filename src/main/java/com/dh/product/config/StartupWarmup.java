package com.dh.product.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Ready 전에 공개 목록 경로를 자기 자신에게 몇 번 호출해 데운다(product.api#84).
 *
 * <p>replicas 1 이라 Ready 순간 전 트래픽이 새 파드로 넘어가는데, 그때까지 한 번도 실행되지 않은
 * 경로는 첫 요청에서 DispatcherServlet 초기화·Hibernate 쿼리 플랜·Jackson 직렬화기·커넥션 확보를
 * 한꺼번에 치른다. CPU limit 500m 에서 이게 3초를 넘겨 게이트웨이 타임리미터가 503 을 줬다
 * (#72 배포 직후 실측: 첫 {@code /api/products} 3.2s, 이후 median 0.28s).
 *
 * <p>서비스 메서드가 아니라 HTTP 로 부르는 이유: 서블릿·인터셉터·직렬화까지 같이 데워야 한다.
 * {@link ApplicationRunner} 는 {@code ApplicationReadyEvent} 전에 끝나므로 readiness 가
 * ACCEPTING_TRAFFIC 이 되기 전에 실행된다. 실패는 기동을 막지 않는다 - 워밍업이 안 된 것뿐이다.
 */
@Component
@ConditionalOnProperty(name = "product.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class StartupWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

    private static final String[] PATHS = {"/api/products"};

    private final Environment environment;
    private final int iterations;

    public StartupWarmup(Environment environment, @Value("${product.warmup.iterations:5}") int iterations) {
        this.environment = environment;
        this.iterations = iterations;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 웹 서버가 없는 컨텍스트(WebEnvironment.NONE 테스트 등)에는 부를 대상이 없다.
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            return;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (String path : PATHS) {
            URI uri = URI.create("http://localhost:" + port + path);
            for (int i = 1; i <= iterations; i++) {
                long started = System.nanoTime();
                try {
                    HttpResponse<Void> res = client.send(
                            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    log.info("warmup {} #{} -> {} in {}ms", path, i, res.statusCode(),
                            (System.nanoTime() - started) / 1_000_000);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("warmup {} #{} failed: {}", path, i, e.toString());
                    break;
                }
            }
        }
    }
}
