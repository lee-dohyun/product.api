package com.dh.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dh.product.domain.Product;
import com.dh.product.domain.WishlistItem;
import com.dh.product.repository.ProductRepository;
import com.dh.product.repository.WishlistRepository;

@ExtendWith(MockitoExtension.class)
class WishlistServiceTest {

    @Mock
    private WishlistRepository wishlistRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private WishlistService wishlistService;

    @Test
    @DisplayName("찜 추가 시 정상적으로 저장된다")
    void addWishlist_Success() {
        // given
        String userId = "user-123";
        Long productId = 1L;
        Product product = new Product();
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", productId);
        product.setName("Test Product");

        given(productRepository.findById(productId)).willReturn(Optional.of(product));
        given(wishlistRepository.existsByUserIdAndProductId(userId, productId)).willReturn(false);

        WishlistItem savedItem = new WishlistItem(userId, product);
        org.springframework.test.util.ReflectionTestUtils.setField(savedItem, "id", 100L);
        given(wishlistRepository.save(any(WishlistItem.class))).willReturn(savedItem);

        // when
        Long id = wishlistService.addWishlist(userId, productId);

        // then
        assertThat(id).isEqualTo(100L);
        verify(wishlistRepository).save(any(WishlistItem.class));
    }

    @Test
    @DisplayName("존재하지 않는 상품을 찜하려고 하면 예외가 발생한다")
    void addWishlist_ProductNotFound() {
        // given
        given(productRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> wishlistService.addWishlist("user-123", 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product not found");
        
        verify(wishlistRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 찜한 상품을 다시 찜하려고 하면 예외가 발생한다")
    void addWishlist_AlreadyExists() {
        // given
        String userId = "user-123";
        Long productId = 1L;
        given(wishlistRepository.existsByUserIdAndProductId(userId, productId)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> wishlistService.addWishlist(userId, productId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Already added to wishlist");
        
        verify(wishlistRepository, never()).save(any());
    }

    @Test
    @DisplayName("사용자의 찜 목록을 조회한다")
    void getWishlists() {
        // given
        String userId = "user-123";
        Product product = new Product();
        product.setName("Test");
        WishlistItem item = new WishlistItem(userId, product);
        given(wishlistRepository.findByUserId(userId)).willReturn(List.of(item));

        // when
        List<WishlistItem> results = wishlistService.getWishlists(userId);

        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("찜 항목을 삭제한다")
    void removeWishlist() {
        // given
        String userId = "user-123";
        Long productId = 1L;
        WishlistItem item = new WishlistItem(userId, new Product());
        given(wishlistRepository.findByUserIdAndProductId(userId, productId)).willReturn(Optional.of(item));

        // when
        wishlistService.removeWishlist(userId, productId);

        // then
        verify(wishlistRepository).delete(item);
    }
}
