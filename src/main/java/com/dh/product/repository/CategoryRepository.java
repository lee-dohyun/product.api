package com.dh.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.dh.product.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * 채널의 전체 카테고리를 노출 순서대로.
     *
     * <p>정렬 키가 {@code (sortOrder, id)} 인 이유: sortOrder 만으로는 값이 같은 형제끼리
     * 순서가 정해지지 않아 다시 힙 순서(=비결정적)에 맡겨진다. id 를 tie-break 로 붙여야
     * 어떤 데이터 상태에서도 응답이 결정적이다.
     *
     * <p>부모별로 묶지 않고 평면 목록 그대로 반환한다 - 소비자(posselect-shell Header,
     * admin.front)가 parentId 로 직접 묶고 있고, 그 과정에서 배열 순서를 보존한다.
     */
    List<Category> findByChannelIdOrderBySortOrderAscIdAsc(Long channelId);

    /** 전체 카테고리를 노출 순서대로. MainPageService 의 대분류 순회가 이 순서를 따른다. */
    List<Category> findAllByOrderBySortOrderAscIdAsc();

    /** 같은 부모 아래 형제들. 순서 변경(위/아래 이동)이 이 목록 위에서 이뤄진다. */
    @Query("""
            SELECT c FROM Category c
             WHERE c.channel.id = :channelId
               AND ((:parentId IS NULL AND c.parent IS NULL) OR c.parent.id = :parentId)
             ORDER BY c.sortOrder ASC, c.id ASC
            """)
    List<Category> findSiblings(Long channelId, Long parentId);

    /** 이 카테고리를 부모로 삼는 하위 카테고리 수. 삭제 차단 판정에 쓴다. */
    long countByParentId(Long parentId);
}
