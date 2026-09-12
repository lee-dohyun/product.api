package com.dh.product.service;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.product.config.CacheNames;
import com.dh.product.domain.Category;
import com.dh.product.domain.Channel;
import com.dh.product.dto.ProductDtos.CategoryCreateRequest;
import com.dh.product.dto.ProductDtos.CategoryResponse;
import com.dh.product.dto.ProductDtos.CategoryUpdateRequest;
import com.dh.product.repository.CategoryRepository;
import com.dh.product.repository.ChannelRepository;
import com.dh.product.repository.ProductRepository;

/**
 * 카테고리 읽기/쓰기.
 *
 * <p><b>클래스 레벨에 {@code readOnly = true} 를 붙이지 않는다.</b> 이 저장소는 읽기 전용
 * 클래스에 쓰기 경로를 얹었다가 UPDATE 가 조용히 사라진 사고가 있었다(캐논 §3, posselect #211).
 * 조회 메서드에만 개별적으로 {@code readOnly} 를 준다.
 *
 * <p>멱등성: 이 서비스의 쓰기는 전부 특정 id 를 대상으로 하는 전체 교체(PUT)이거나 삭제라
 * 같은 요청을 두 번 보내도 결과가 같다. 생성(POST)만 예외인데, (channel, parent, name)
 * 유니크 제약이 중복 생성을 DB 레벨에서 막고 여기서 409 로 바꾼다.
 */
@Service
public class CategoryService {

    /** 카탈로그는 대분류-중분류 2뎁스만 쓴다({@code Category} 주석, posselect-shell 카테고리 패널). */
    private static final int MAX_DEPTH = 2;

    private final CategoryRepository categoryRepository;
    private final ChannelRepository channelRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository,
                           ChannelRepository channelRepository,
                           ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(Long channelId) {
        return categoryRepository.findByChannelIdOrderBySortOrderAscIdAsc(channelId).stream()
                .map(CategoryService::toResponse)
                .toList();
    }

    /**
     * 새 카테고리는 형제들 맨 뒤에 붙인다. sortOrder 를 요청받지 않는 이유: 생성 화면에서
     * 순서를 정하게 하면 기존 형제들과의 충돌을 화면이 알아야 하는데, 만들자마자 목록에서
     * 위/아래로 옮기는 편이 단순하다.
     */
    @CacheEvict(cacheNames = CacheNames.MAIN_BY_CATEGORY, allEntries = true)
    @Transactional
    public CategoryResponse create(Long channelId, CategoryCreateRequest request) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new NoSuchElementException("channel not found: " + channelId));

        Category category = new Category();
        category.setName(request.name().trim());
        category.setChannel(channel);
        if (request.parentId() != null) {
            category.setParent(loadParent(request.parentId(), channelId));
        }
        category.setSortOrder(nextSortOrder(channelId, request.parentId()));

        return toResponse(saveOrConflict(category));
    }

    /**
     * 이름/상위/노출순서를 한 번에 교체한다.
     *
     * <p>{@code product} 캐시까지 비우는 이유: 상품 상세 응답({@code ProductResponse})이
     * 카테고리 이름을 통째로 품고 있어서, 이름만 바꾸고 상품 캐시를 그대로 두면 최대 10분 동안
     * 상세 화면에 옛 이름이 남는다. 키가 상품 id 라 어떤 상품이 걸리는지 특정할 수 없어
     * {@code allEntries} 로 비운다 — 카테고리 수정은 드문 조작이라 비용이 문제되지 않는다.
     *
     * <p>{@code main-best}/{@code main-new} 는 일부러 비우지 않는다. 두 캐시가 담는
     * {@code ProductSummaryResponse} 에는 카테고리 <b>이름</b>이 없고 {@code categoryId} 만
     * 있어서, 이름·순서를 바꿔도 그 응답의 내용이 달라지지 않는다.
     *
     * <p><b>남겨 둔 레이스</b>(cache-invalidation-guard 5번): {@code @CacheEvict} 와
     * {@code @Transactional} 이 같은 메서드에 붙어 있어 커밋 직전에 evict 가 일어난다.
     * 그 찰나에 다른 요청이 옛 행을 읽어 캐시를 다시 채우면 최대 10분간 옛 카테고리 이름이
     * 남는다. 관리자만 쓰는 드문 조작이고 결과가 표시 이름 지연이라, afterCommit 훅을
     * 도입하는 대신 알려진 한계로 남긴다.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.PRODUCT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.MAIN_BY_CATEGORY, allEntries = true)
    })
    @Transactional
    public CategoryResponse update(Long id, CategoryUpdateRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("category not found: " + id));
        Long channelId = category.getChannel().getId();

        Category newParent = request.parentId() == null ? null : loadParent(request.parentId(), channelId);
        validateHierarchy(category, newParent);

        category.setName(request.name().trim());
        category.setParent(newParent);
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }

        return toResponse(saveOrConflict(category));
    }

    /**
     * 하위 카테고리나 상품이 달려 있으면 지우지 않고 409 로 거부한다.
     *
     * <p>{@code products.category_id} 는 NOT NULL FK 라 그냥 지우면 DB 제약 위반이 그대로
     * 500 으로 나간다. 무엇이 몇 개 걸려서 막혔는지 메시지에 담아, 관리자가 다음에 할 일
     * (상품을 다른 카테고리로 옮기기)을 알 수 있게 한다.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.PRODUCT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.MAIN_BY_CATEGORY, allEntries = true)
    })
    @Transactional
    public void delete(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("category not found: " + id));

        long children = categoryRepository.countByParentId(id);
        long products = productRepository.countByCategoryId(id);
        if (children > 0 || products > 0) {
            throw new IllegalStateException(String.format(
                    "하위 카테고리 %d개, 상품 %d개가 연결되어 있어 삭제할 수 없습니다. 먼저 옮기거나 지워 주세요.",
                    children, products));
        }

        categoryRepository.delete(category);
    }

    /**
     * 새 부모 후보를 읽어온다. 다른 채널의 카테고리를 부모로 지정하면 채널 경계가 무너지므로
     * 존재하지 않는 것과 같이 취급한다(404).
     */
    private Category loadParent(Long parentId, Long channelId) {
        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new NoSuchElementException("parent category not found: " + parentId));
        if (!parent.getChannel().getId().equals(channelId)) {
            throw new NoSuchElementException("parent category not found: " + parentId);
        }
        return parent;
    }

    /**
     * 2뎁스 제약과 순환 참조를 함께 검사한다.
     *
     * <p>순환은 "자기 자신을 부모로" 만이 아니다 - 부모 사슬을 타고 올라가다 자기 자신을 다시
     * 만나는 경우가 전부 순환이다. 지금은 2뎁스라 사슬이 짧지만, 데이터가 어긋나 더 깊어져도
     * 무한 루프에 빠지지 않도록 방문 깊이에 상한을 둔다({@code MainPageService.resolveRootId}
     * 가 같은 이유로 상한을 두고 있다).
     */
    private void validateHierarchy(Category category, Category newParent) {
        if (newParent == null) {
            return;
        }
        if (newParent.getId().equals(category.getId())) {
            throw new CategoryHierarchyException("카테고리를 자기 자신의 하위로 옮길 수 없습니다.");
        }

        Category cursor = newParent;
        for (int depth = 0; cursor != null && depth <= MAX_DEPTH + 1; depth++) {
            if (cursor.getId().equals(category.getId())) {
                throw new CategoryHierarchyException("카테고리를 자기 하위 카테고리 밑으로 옮길 수 없습니다.");
            }
            cursor = cursor.getParent();
        }

        if (newParent.getParent() != null) {
            throw new CategoryHierarchyException(
                    "카테고리는 2단계까지만 지원합니다. 하위 카테고리를 다시 상위로 지정할 수 없습니다.");
        }
        if (categoryRepository.countByParentId(category.getId()) > 0) {
            throw new CategoryHierarchyException(
                    "하위 카테고리가 있는 카테고리는 다른 카테고리 밑으로 옮길 수 없습니다(2단계 초과).");
        }
    }

    /** 형제 중 가장 큰 sortOrder + 1. 형제가 없으면 1. */
    private short nextSortOrder(Long channelId, Long parentId) {
        return (short) (categoryRepository.findSiblings(channelId, parentId).stream()
                .mapToInt(Category::getSortOrder)
                .max()
                .orElse(0) + 1);
    }

    /**
     * (channel, parent, name) 유니크 제약 위반을 409 로 바꾼다.
     *
     * <p>{@code saveAndFlush} 인 이유: 그냥 {@code save} 하면 INSERT/UPDATE 가 트랜잭션
     * 커밋 시점까지 미뤄져 이 try 블록 밖에서 터진다. 그러면 여기서 잡을 수 없어 500 이 나간다.
     *
     * <p>전역 {@code DataIntegrityViolationException} 핸들러를 두지 않고 여기서 국소적으로
     * 처리하는 이유는 {@link CategoryHierarchyException} 과 같다 - 다른 엔드포인트의 응답
     * 코드를 같이 바꾸지 않기 위해서다.
     */
    private Category saveOrConflict(Category category) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("같은 상위 카테고리 아래에 이미 같은 이름의 카테고리가 있습니다.", e);
        }
    }

    private static CategoryResponse toResponse(Category category) {
        Long parentId = category.getParent() != null ? category.getParent().getId() : null;
        return new CategoryResponse(category.getId(), category.getName(), parentId, category.getSortOrder());
    }
}
