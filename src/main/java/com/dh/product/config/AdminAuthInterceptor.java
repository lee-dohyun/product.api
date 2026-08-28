package com.dh.product.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 관리 쓰기 API(POST/PUT/DELETE)는 admin.front(Keycloak staff realm 로그인)만 호출할 수 있어야
// 하므로, admin.front가 전달하는 Authorization: Bearer 토큰을 직접 재검증한다. GET은 그대로 공개.
//
// 토큰이 유효한지만 보는 것으로는 부족하다. admin.front(lib/menu.ts)는 경로마다 필요 역할을
// 다르게 두는데, 백엔드가 역할을 안 보면 ORDER_MANAGER 처럼 상품을 건드리면 안 되는 계정이
// 이 API 를 직접 호출해 그 제한을 우회할 수 있다(product.api#25). admin.front 의 미들웨어는
// admin.posselect.com 을 거칠 때만 도는 방어선이라 여기서 다시 막아야 한다.
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuthInterceptor.class);

    /** 인증된 {@link AdminPrincipal}을 담아두는 요청 속성 키. 컨트롤러가 "누가 했는가"를
     * 기록해야 할 때(예: SellerController의 상태 전이 이력) 토큰을 다시 검증하지 않고 꺼내 쓴다. */
    public static final String PRINCIPAL_ATTRIBUTE = "adminPrincipal";

    // 경로 접두사 -> 필요 역할. admin.front lib/menu.ts 의 apiPrefixes/requiredRoles 와
    // 값이 같아야 한다. 새 경로를 추가하면 여기와 WebConfig.addPathPatterns 둘 다 등록할 것 -
    // 등록하지 않은 경로는 매칭되는 인터셉터가 없어 그대로 통과한다.
    private static final Map<String, String> PATH_ROLES = Map.of(
            "/api/products", "PRODUCT_MANAGER",
            "/api/categories", "PRODUCT_MANAGER",
            "/api/sellers", "PARTNER");

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
        String requiredRole = resolveRequiredRole(request.getRequestURI());
        String authHeader = request.getHeader("Authorization");
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length())
                : null;
        AdminPrincipal admin = verifier.verify(token);
        if (admin == null) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        if (!admin.hasAnyRole(requiredRole)) {
            logger.warn("admin write 거부(역할 부족): {} {} by {} roles={}",
                    request.getMethod(), request.getRequestURI(), admin.email(), admin.roles());
            response.sendError(HttpStatus.FORBIDDEN.value(), "admin only");
            return false;
        }
        logger.info("admin write: {} {} by {}", request.getMethod(), request.getRequestURI(), admin.email());
        request.setAttribute(PRINCIPAL_ATTRIBUTE, admin);
        return true;
    }

    /** 가장 구체적으로(긴 접두사로) 매칭되는 역할을 고른다 - 경로가 여러 접두사에 걸리는 일은 없지만 안전하게. */
    private String resolveRequiredRole(String uri) {
        return PATH_ROLES.entrySet().stream()
                .filter(e -> uri.equals(e.getKey()) || uri.startsWith(e.getKey() + "/"))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(Map.Entry::getValue)
                // 이 인터셉터가 등록된 경로(WebConfig.addPathPatterns)인데 PATH_ROLES에 없으면
                // 설정 누락이다 - 통과시키지 않고 존재할 수 없는 역할을 요구해 항상 거부되게 한다.
                .orElse("__UNCONFIGURED__");
    }
}
