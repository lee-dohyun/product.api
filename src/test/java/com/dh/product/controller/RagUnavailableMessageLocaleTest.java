package com.dh.product.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dh.product.config.LocaleConfig;
import com.dh.product.service.rag.RagUnavailableException;

/**
 * 상품 Q&A 를 쓸 수 없을 때(키 미등록, OpenAI 빈 응답)의 503 본문 (gateway#68).
 * 예외 메시지는 운영자용 원인("OPENAI_API_KEY가 설정되지 않아…")이라 고객 응답에 그대로 실으면
 * 번역도 안 되고 내부 설정 이름까지 드러난다 — 본문은 요청 로케일의 안내 문구여야 한다.
 */
class RagUnavailableMessageLocaleTest {

    private static final String INTERNAL_CAUSE = "OPENAI_API_KEY가 설정되지 않아 임베딩을 생성할 수 없습니다";

    @RestController
    static class ThrowingController {
        @GetMapping("/test/qa")
        String qa() {
            throw new RagUnavailableException(INTERNAL_CAUSE);
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler(source))
                .setLocaleResolver(new LocaleConfig().localeResolver())
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(
                        java.nio.charset.StandardCharsets.UTF_8))
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();
    }

    @Test
    @DisplayName("503 은 그대로, 본문은 한국어 안내 문구이고 내부 원인은 싣지 않는다")
    void 기본은_한국어_안내() throws Exception {
        mvc.perform(get("/test/qa"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("AI 답변 기능을 지금은 사용할 수 없습니다."))
                .andExpect(content().string(not(containsString("OPENAI_API_KEY"))));
    }

    @Test
    @DisplayName("Accept-Language 로 영어·일본어·중국어")
    void accept_language() throws Exception {
        mvc.perform(get("/test/qa").header("Accept-Language", "en"))
                .andExpect(content().string("The AI answer feature is not available right now."));
        mvc.perform(get("/test/qa").header("Accept-Language", "ja"))
                .andExpect(content().string("AI回答機能は現在ご利用いただけません。"));
        mvc.perform(get("/test/qa").header("Accept-Language", "zh-CN"))
                .andExpect(content().string("AI 回答功能目前无法使用。"));
    }
}
