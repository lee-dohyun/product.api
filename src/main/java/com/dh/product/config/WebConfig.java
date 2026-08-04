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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/products/**", "/api/categories/**");
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
