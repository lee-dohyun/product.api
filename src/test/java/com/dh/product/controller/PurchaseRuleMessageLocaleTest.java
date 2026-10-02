package com.dh.product.controller;

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
import com.dh.product.service.PurchaseRuleViolationException;

/**
 * 구매 불가 사유가 요청 로케일로 내려가는지 — 실제 messages 번들과 LocaleResolver 를 물려서 본다(gateway#68).
 * 키만 맞고 번들·리졸버 배선이 빠지면 고객은 키 문자열("purchase.…")을 그대로 보게 된다.
 */
class PurchaseRuleMessageLocaleTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/test/max")
        String max() {
            throw new PurchaseRuleViolationException("purchase.maxQuantityExceeded", 3);
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
                // standalone MockMvc 의 문자열 컨버터 기본값은 ISO-8859-1 이다. 실제 서버는 Spring Boot 가 UTF-8 컨버터를 건다.
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(
                        java.nio.charset.StandardCharsets.UTF_8))
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();
    }

    @Test
    @DisplayName("헤더가 없으면 한국어, 409 는 그대로")
    void 기본은_한국어() throws Exception {
        mvc.perform(get("/test/max"))
                .andExpect(status().isConflict())
                .andExpect(content().string("이 상품은 1회 최대 3개까지 구매할 수 있습니다."));
    }

    @Test
    @DisplayName("Accept-Language 로 영어·일본어·중국어")
    void accept_language() throws Exception {
        mvc.perform(get("/test/max").header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(content().string("You can buy up to 3 of this product per order."));
        mvc.perform(get("/test/max").header("Accept-Language", "ja"))
                .andExpect(content().string("この商品は1回につき最大3個まで購入できます。"));
        mvc.perform(get("/test/max").header("Accept-Language", "zh-CN"))
                .andExpect(content().string("该商品每次最多可购买3件。"));
    }

    @Test
    @DisplayName("X-Locale 이 Accept-Language 보다 우선하고, 지원하지 않는 언어는 한국어로 떨어진다")
    void x_locale_우선_및_폴백() throws Exception {
        mvc.perform(get("/test/max").header("X-Locale", "en").header("Accept-Language", "ja"))
                .andExpect(content().string("You can buy up to 3 of this product per order."));
        mvc.perform(get("/test/max").header("Accept-Language", "de"))
                .andExpect(content().string("이 상품은 1회 최대 3개까지 구매할 수 있습니다."));
    }
}
