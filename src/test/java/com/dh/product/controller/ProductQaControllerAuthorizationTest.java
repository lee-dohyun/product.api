package com.dh.product.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dh.product.dto.ProductQaDtos.ProductQaResponse;
import com.dh.product.service.rag.ProductQaService;

/**
 * product.api#58 회귀 방지.
 *
 * <p>Q&A 는 고객용 POST 인데 경로가 {@code /api/products/qa} 라, 관리 쓰기를 막는
 * {@code AdminAuthInterceptor}({@code /api/products/**} 에 등록)에 그대로 걸려 403 이었다.
 * 인가는 인터셉터 등록(WebConfig) 레벨의 문제라 인터셉터 단위 테스트로는 잡히지 않는다 —
 * 실제 MVC 배선을 통과시켜야 한다. 관리 경로가 같이 열려버리는 반대 방향의 회귀도 함께 막는다.
 */
@WebMvcTest({ ProductQaController.class, CategoryController.class })
class ProductQaControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductQaService productQaService;

    @MockitoBean
    private com.dh.product.config.AdminJwtVerifier adminJwtVerifier;

    // CategoryController 는 리포지토리가 아니라 CategoryService 에 의존한다(product.api#61).
    // 이 테스트가 보는 것은 인터셉터 배선뿐이라 서비스 동작은 필요 없지만, 빈이 없으면
    // @WebMvcTest 컨텍스트 자체가 뜨지 않는다.
    @MockitoBean
    private com.dh.product.service.CategoryService categoryService;

    @Test
    @DisplayName("고객 Q&A 는 staff 토큰 없이 호출할 수 있다")
    void qaIsOpenToCustomers() throws Exception {
        given(productQaService.answer(anyString()))
                .willReturn(new ProductQaResponse("답변", List.of()));

        mockMvc.perform(post("/api/products/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"가벼운 노트북 추천해줘\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리 쓰기 경로는 여전히 staff 토큰 없이는 막힌다")
    void adminWriteStaysProtected() throws Exception {
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규\"}"))
                .andExpect(status().isForbidden());
    }
}
