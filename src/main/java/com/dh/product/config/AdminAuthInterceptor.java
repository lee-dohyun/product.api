package com.dh.product.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 상품/카테고리 쓰기 API(POST/PUT/DELETE)는 admin.front만 호출할 수 있어야 하므로,
// admin.front와만 공유하는 비밀값을 헤더로 요구한다. GET은 그대로 공개.
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final String adminSecret;

    public AdminAuthInterceptor(@Value("${admin.shared-secret:}") String adminSecret) {
        this.adminSecret = adminSecret;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String provided = request.getHeader("X-Admin-Secret");
        if (adminSecret.isBlank() || provided == null || !adminSecret.equals(provided)) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        return true;
    }
}
