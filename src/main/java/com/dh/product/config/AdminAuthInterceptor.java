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
//
// 토큰이 유효한지만 보는 것으로는 부족하다. admin.front(lib/menu.ts)는 상품/카테고리 관리를
// PRODUCT_MANAGER 또는 SYSTEM_ADMIN 으로 제한하는데, 백엔드가 역할을 안 보면 ORDER_MANAGER 처럼
// 상품을 건드리면 안 되는 계정이 이 API 를 직접 호출해 그 제한을 우회할 수 있다(product.api#25).
// admin.front 의 미들웨어는 admin.posselect.com 을 거칠 때만 도는 방어선이라 여기서 다시 막아야 한다.
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuthInterceptor.class);

    // admin.front lib/menu.ts 의 "상품 관리" / "카테고리 관리" requiredRoles 와 같은 값이어야 한다.
    private static final String PRODUCT_MANAGER = "PRODUCT_MANAGER";

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
        AdminPrincipal admin = verifier.verify(token);
        if (admin == null) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        if (!admin.hasAnyRole(PRODUCT_MANAGER)) {
            logger.warn("admin write 거부(역할 부족): {} {} by {} roles={}",
                    request.getMethod(), request.getRequestURI(), admin.email(), admin.roles());
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        logger.info("admin write: {} {} by {}", request.getMethod(), request.getRequestURI(), admin.email());
        return true;
    }
}
