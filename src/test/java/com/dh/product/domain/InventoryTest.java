package com.dh.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InventoryTest {

    private Inventory inventory(int quantity) {
        return new Inventory(new ProductVariant(), quantity);
    }

    @Test
    void 요청량만큼_차감한다() {
        Inventory inventory = inventory(10);

        inventory.deduct(3);

        assertThat(inventory.getQuantity()).isEqualTo(7);
    }

    @Test
    void 재고와_같은_수량까지는_차감된다() {
        Inventory inventory = inventory(5);

        inventory.deduct(5);

        assertThat(inventory.getQuantity()).isZero();
    }

    /** 음수 재고는 어떤 경로로도 만들어지면 안 된다. DB CHECK 제약(V3)은 이것의 최후 방어선이다. */
    @Test
    void 재고보다_많이_차감하면_예외이고_수량은_그대로다() {
        Inventory inventory = inventory(2);

        assertThatThrownBy(() -> inventory.deduct(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고가 부족합니다");
        assertThat(inventory.getQuantity()).isEqualTo(2);
    }

    @Test
    void 복원하면_수량이_돌아온다() {
        Inventory inventory = inventory(4);

        inventory.deduct(3);
        inventory.restore(3);

        assertThat(inventory.getQuantity()).isEqualTo(4);
    }
}
