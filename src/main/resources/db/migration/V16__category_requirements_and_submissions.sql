-- =========================================================
-- 상품 등록 검증 규칙 엔진 2단계 (product.api#30, gateway#212)
--
-- 왜 포털보다 이게 먼저인가: 협력사 포털의 본질은 화면이 아니라 검증 규칙 엔진이다.
-- 규칙 없는 입력창을 외부인에게 먼저 열면 검수가 전부 사람 눈으로 내려온다.
--
-- 법적 배경: 「전자상거래 등에서의 상품 등의 정보제공에 관한 고시」가 30여 개 품목군에 대해
-- 원산지·제조국·제조연월일·유통기한·소재·A/S 책임자 등을 청약 전에 제공하도록 의무화한다.
-- 현재 상품 등록 폼에는 고시 항목이 한 칸도 없다 - 유상 판매를 시작하는 순간 법 위반이다.
--
-- 고시 항목 정의를 코드 상수가 아니라 테이블에 두는 이유: 고시는 개정된다. 개정 때마다
-- 배포하지 않으려면 데이터여야 한다.
-- =========================================================

-- ---------------------------------------------------------
-- 1) category_requirements — 카테고리를 규칙의 앵커로 삼는다
--
-- required_attributes / required_documents 를 jsonb 가 아니라 TEXT 로 두는 이유:
-- 이 값들은 SQL 로 질의하지 않고 검증 엔진이 통째로 읽어 순회하기만 한다. 반면 jsonb 를
-- 쓰려면 Hibernate 쪽에 @JdbcTypeCode(SqlTypes.JSON) 매핑이 필요한데, ddl-auto: validate
-- 환경에서 타입 검증이 어긋나면 부팅 자체가 막힌다(이 저장소는 그 사고를 이미 겪었다 - V4).
-- 질의 요구가 실제로 생기면 그때 jsonb 로 옮긴다.
-- ---------------------------------------------------------
CREATE TABLE category_requirements (
    id                   BIGSERIAL PRIMARY KEY,
    category_id          BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    required_attributes  TEXT NOT NULL DEFAULT '[]',
    required_documents   TEXT NOT NULL DEFAULT '[]',
    commission_rate      NUMERIC(5,2) NULL,
    restricted           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_requirements_category UNIQUE (category_id)
);

COMMENT ON COLUMN category_requirements.required_attributes IS
    '고시 품목군별 필수 항목 정의(JSON 배열): [{"code":"origin","label":"원산지","required":true}, ...]';
COMMENT ON COLUMN category_requirements.required_documents IS
    '인허가 서류 코드(JSON 배열): ["FOOD_BUSINESS_LICENSE", ...] - 판매권한 심사(게이트 04)에서 쓴다';
COMMENT ON COLUMN category_requirements.commission_rate IS
    '카테고리 수수료율. 3P 전환 시 정산에 쓴다 - 지금은 자리만 잡아 둔다';
COMMENT ON COLUMN category_requirements.restricted IS
    '사전 판매권한이 필요한 카테고리인지. TRUE 면 seller_category_permissions 가 있어야 제출이 통과한다';

-- ---------------------------------------------------------
-- 2) product_attributes — 상품별 고시 항목 값
-- ---------------------------------------------------------
CREATE TABLE product_attributes (
    id              BIGSERIAL PRIMARY KEY,
    product_id      BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    attribute_code  VARCHAR(50)  NOT NULL,
    attribute_value VARCHAR(500) NULL,
    CONSTRAINT uq_product_attributes_product_code UNIQUE (product_id, attribute_code)
);

CREATE INDEX idx_product_attributes_product_id ON product_attributes(product_id);

-- ---------------------------------------------------------
-- 3) seller_category_permissions — 판매권한(게이트 04)
--
-- 식품·화장품·건강기능식품처럼 인허가가 필요한 카테고리는 판매자마다 따로 열어 준다.
-- ---------------------------------------------------------
CREATE TABLE seller_category_permissions (
    id          BIGSERIAL PRIMARY KEY,
    seller_id   BIGINT NOT NULL REFERENCES sellers(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    granted_by  VARCHAR(200) NOT NULL,
    granted_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_seller_category_permissions UNIQUE (seller_id, category_id)
);

CREATE INDEX idx_seller_category_permissions_seller_id ON seller_category_permissions(seller_id);

-- ---------------------------------------------------------
-- 4) product_submissions — 상품 검수 제출 + 상태머신
--
--   DRAFT → SUBMITTED → VALIDATING → IN_REVIEW → LIVE ⇄ PAUSED
--               ↑            ↓
--            (재제출)     NEEDS_FIX
--
-- VALIDATING 을 사람 심사(IN_REVIEW) 앞에 두는 게 핵심이다. 규칙이 걸러낸 것만 사람 큐에
-- 올라간다 - 순서를 반대로 두면 사람이 오탈자를 잡는 데 시간을 쓴다.
-- 전이 검증은 ProductSubmissionService 가 맡는다(CHECK 제약으로 표현하면 상태가 늘 때마다
-- 마이그레이션이 필요해진다 - V14 sellers 와 같은 판단).
-- ---------------------------------------------------------
CREATE TABLE product_submissions (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    seller_id    BIGINT NOT NULL REFERENCES sellers(id),
    status       VARCHAR(20)  NOT NULL,
    submitted_by VARCHAR(200) NOT NULL,
    reviewed_by  VARCHAR(200) NULL,
    review_note  VARCHAR(500) NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_submissions_status ON product_submissions(status);
CREATE INDEX idx_product_submissions_product_id ON product_submissions(product_id);

COMMENT ON COLUMN product_submissions.status IS
    'DRAFT/SUBMITTED/VALIDATING/NEEDS_FIX/IN_REVIEW/LIVE/PAUSED - 전이 검증은 ProductSubmissionService';

-- ---------------------------------------------------------
-- 5) submission_issues — 필드 단위 검증 결과
--
-- 제출 1건이 재검증되면 이전 이슈는 지우고 새로 쓴다(append 가 아니다) - "지금 이 제출에
-- 남아 있는 문제"가 조회의 목적이기 때문이다. 심사 이력은 product_submissions 쪽에 남는다.
--
-- 사유를 자유 텍스트가 아니라 code + message 로 나눠 담는 이유: 조사 결과 반려 사유가
-- 정형화되어 있고(상호·주소 불일치 / 카테고리 인증 누락 / 서류 발급일 초과 / 채널명 불일치),
-- 코드로 두면 나중에 자동 검사로 승격하거나 통계를 낼 수 있다.
-- ---------------------------------------------------------
CREATE TABLE submission_issues (
    id            BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES product_submissions(id) ON DELETE CASCADE,
    code          VARCHAR(50)  NOT NULL,
    field         VARCHAR(100) NULL,
    message       VARCHAR(500) NOT NULL,
    severity      VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_submission_issues_submission_id ON submission_issues(submission_id);

COMMENT ON COLUMN submission_issues.severity IS 'BLOCKING(제출 불가) / WARNING(통과하되 표시)';
COMMENT ON COLUMN submission_issues.field IS '문제가 있는 입력 칸. 폼이 인라인으로 표시할 수 있게 필드 단위로 남긴다';

-- ---------------------------------------------------------
-- 6) 고시 항목 시드 — 식품/화장품
--
-- 전 품목군(30여 개)을 지금 다 넣지 않는다. 규칙 엔진이 실제로 도는지 검증할 수 있는
-- 최소 세트만 넣고, 나머지는 관리 화면이 생긴 뒤 데이터로 추가한다(그러라고 테이블에 뒀다).
--
-- 식품·화장품을 고른 이유: 둘 다 restricted(인허가 필요) 카테고리라 판매권한 게이트까지
-- 함께 검증된다.
-- ---------------------------------------------------------
INSERT INTO category_requirements (category_id, required_attributes, required_documents, restricted)
SELECT c.id,
       '[{"code":"origin","label":"원산지","required":true},'
    || ' {"code":"manufacturer","label":"제조사","required":true},'
    || ' {"code":"expiry","label":"소비기한","required":true},'
    || ' {"code":"storage","label":"보관방법","required":true},'
    || ' {"code":"as_contact","label":"소비자상담 관련 전화번호","required":true}]',
       '["FOOD_BUSINESS_LICENSE"]',
       TRUE
  FROM categories c
 WHERE c.id IN (9108, 9109, 9110, 9111);

INSERT INTO category_requirements (category_id, required_attributes, required_documents, restricted)
SELECT c.id,
       '[{"code":"capacity","label":"내용물의 용량 또는 중량","required":true},'
    || ' {"code":"manufacturer","label":"제조업자","required":true},'
    || ' {"code":"expiry","label":"사용기한 또는 개봉 후 사용기간","required":true},'
    || ' {"code":"ingredients","label":"전성분","required":true},'
    || ' {"code":"as_contact","label":"소비자상담 관련 전화번호","required":true}]',
       '["COSMETICS_MANUFACTURE_REPORT"]',
       TRUE
  FROM categories c
 WHERE c.id IN (9105, 9106, 9107);

-- 자사(seller_id=1)는 위 카테고리 판매권한을 갖는다 - V8 데모 카탈로그에 이미 식품/화장품
-- 상품이 들어 있어서, 권한을 안 주면 자사 상품이 제출 단계에서 전부 막힌다.
INSERT INTO seller_category_permissions (seller_id, category_id, granted_by)
SELECT 1, cr.category_id, 'system'
  FROM category_requirements cr
 WHERE cr.restricted;
