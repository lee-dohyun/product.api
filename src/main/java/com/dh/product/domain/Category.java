package com.dh.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private Channel channel;

    // 최상위 카테고리는 null. 2뎁스만 지원(하위 카테고리가 또 하위를 갖는 3뎁스는 현재 UI가
    // 전제하지 않음 - posselect-shell 카테고리 패널 주석 참고).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    // 형제(같은 부모) 안에서의 노출 순서. 조회는 항상 (sort_order, id) 로 정렬한다 -
    // sort_order 만으로 정렬하면 값이 같은 형제끼리 다시 힙 순서에 맡겨지기 때문이다.
    // 이 컬럼이 없던 동안 목록에 ORDER BY 자체가 없어서, 카테고리를 한 번이라도 UPDATE 하면
    // 그 행이 힙 끝으로 밀려 헤더 메뉴 순서가 뒤바뀌었다(V17 주석에 실측 근거).
    @Column(name = "sort_order", nullable = false)
    private Short sortOrder = 0;
}
