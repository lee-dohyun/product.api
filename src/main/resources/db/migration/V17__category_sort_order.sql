-- 카테고리 노출 순서 컬럼.
--
-- 왜 필요한가: /api/categories 와 MainPageService 의 카테고리 조회에는 ORDER BY 가 없었다.
-- 지금까지 id 순으로 보인 것은 V8 시드가 한 번에 INSERT 한 뒤 아무도 UPDATE 하지 않아
-- 힙 순서가 그대로 유지된 것일 뿐, 보장된 순서가 아니다. 라이브 DB 에서 직접 확인한 동작:
--
--     before: 1 2 3 4
--     UPDATE ... WHERE id = 2;
--     after : 1 3 4 2      -- 수정된 행이 힙 끝으로 이동
--
-- 즉 이번에 추가하는 "카테고리 이름 수정" 기능을 쓰는 순간, 첫 수정에서 헤더 카테고리
-- 메뉴 순서가 뒤섞인다(posselect-shell Header.tsx / store.front app/page.tsx 는 둘 다
-- API 응답 배열 순서를 그대로 렌더링한다). 정렬 기준을 데이터로 갖는 것이 근본 해결이다.
--
-- 기본값 0 으로 두지 않고 현재 id 순서를 백필하는 이유: 전부 0 이면 동순위가 되어 tie-break
-- 없이는 순서가 다시 힙에 맡겨진다. 조회는 (sort_order, id) 로 정렬하므로 동순위여도
-- 결정적이지만, 기존에 보이던 순서를 그대로 보존하려면 백필이 필요하다.
ALTER TABLE categories ADD COLUMN sort_order SMALLINT NOT NULL DEFAULT 0;

-- 형제(같은 부모, 같은 채널) 안에서 현재 id 순서를 1,2,3... 으로 부여한다.
-- 최상위끼리는 parent_id 가 NULL 이라 IS NOT DISTINCT FROM 으로 묶는다.
WITH ordered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY channel_id, parent_id ORDER BY id) AS rn
      FROM categories
)
UPDATE categories c
   SET sort_order = ordered.rn
  FROM ordered
 WHERE c.id = ordered.id;

CREATE INDEX idx_categories_channel_sort ON categories (channel_id, sort_order, id);
