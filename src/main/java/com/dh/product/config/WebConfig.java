package com.dh.product.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Value("${app.cors-allowed-origin-pattern}")
    private String corsAllowedOriginPattern;

    public WebConfig(AdminAuthInterceptor adminAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    // /api/products/qa 는 경로만 관리 영역 아래에 있을 뿐 고객용 POST 다(product.api#58).
    // 인터셉터는 GET 만 공개하고 나머지 메서드에 staff 토큰 + PRODUCT_MANAGER 를 요구하므로
    // 제외하지 않으면 고객이 영영 호출할 수 없다. 제외는 이 경로 하나로 좁게 둔다 — 하위 경로가
    // 생기면 AdminAuthInterceptor 의 __UNCONFIGURED__ 규칙에 걸려 거부되는 쪽(fail-closed)이
    // 맞고, 그때 공개가 필요하면 여기에 명시적으로 추가할 것.
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/products/**", "/api/categories/**", "/api/sellers/**",
                        "/api/submissions/**")
                .excludePathPatterns("/api/products/qa");
    }

    // posselect-shell(런타임 셸)의 Header/Footer 위젯이 customer.posselect.com/home.posselect.com
    // 페이지 안에서 이 도메인의 API를 브라우저에서 직접 크로스오리진으로 호출한다 — 카테고리는
    // 누구나 볼 수 있는 공개 데이터라 자격증명 없이, 장바구니는 CART_ID 쿠키(product.posselect.com
    // 한정 발급, SameSite=Lax라 same-site 서브도메인 요청엔 그대로 실림)가 필요해 자격증명 포함으로
    // 각각 허용한다.
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/cart/**")
                .allowedOriginPatterns(corsAllowedOriginPattern)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true);
        registry.addMapping("/api/categories/**")
                .allowedOriginPatterns(corsAllowedOriginPattern)
                .allowedMethods("GET", "OPTIONS")
                .allowCredentials(false);
    }
}
