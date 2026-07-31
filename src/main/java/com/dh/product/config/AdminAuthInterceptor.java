package com.dh.product.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 상품/카테고리 쓰기 API(POST/PUT/DELETE)는 admin.front(Keycloak staff realm 로그인)만 호출할 수 있어야
// 하므로, admin.front가 전달하는 Authorization: Bearer 토큰을 직접 재검증한다. GET은 그대로 공개.
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuthInterceptor.class);

    private final AdminJwtVerifier verifier;

    public AdminAuthInterceptor(AdminJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authHeader = request.getHeader("Authorization");
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
        String adminEmail = verifier.verify(token);
        if (adminEmail == null) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        logger.info("admin write: {} {} by {}", request.getMethod(), request.getRequestURI(), adminEmail);
        return true;
    }
}
