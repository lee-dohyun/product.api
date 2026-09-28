package com.dh.product.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PartnerPrincipalTest {

    @Test
    void 이메일이_있으면_이메일() {
        assertThat(new PartnerPrincipal("sub-1", "p@example.com", 1L).actor()).isEqualTo("p@example.com");
    }

    @Test
    void 이메일이_없거나_비면_sub_로_대체_제출자가_null_이_되지_않는다() {
        assertThat(new PartnerPrincipal("sub-1", null, 1L).actor()).isEqualTo("partner:sub-1");
        assertThat(new PartnerPrincipal("sub-1", " ", 1L).actor()).isEqualTo("partner:sub-1");
    }
}
