-- =========================================================
-- product.api#81: 고시 품목군에 생활용품(9005) 하위 3개 카테고리를 추가한다 (2026-09-30 사용자 결정:
-- 식품·화장품 + 생활용품). 근거는 「전자상거래 등에서의 상품 등의 정보제공에 관한 고시」의 품목군.
--
--   9115 주방     → 「주방용품」
--   9116 욕실     → 「기타 재화」(샤워타월·욕실매트·샤워필터 등 섞여 있어 단일 품목군이 없다)
--   9117 세탁청소 → 「생활화학제품」(세제·섬유유연제·제습제가 대부분)
--
-- 인허가가 필요한 품목군이 아니므로 restricted = FALSE, 서류 없음. 조건부 항목(수입품만 해당하는
-- 신고 문구 등)은 required=false 로 둔다 - 해당 없는 상품이 검수에서 막히면 안 된다.
-- 코드는 V16 과 같은 뜻이면 재사용한다(manufacturer, expiry, capacity, as_contact).
-- =========================================================
INSERT INTO category_requirements (category_id, required_attributes, required_documents, restricted)
SELECT 9115, '[{"code":"model_name","label":"품명 및 모델명","required":true}, {"code":"material","label":"재질","required":true}, {"code":"components","label":"구성품","required":true}, {"code":"size","label":"크기","required":true}, {"code":"release_date","label":"동일모델의 출시년월","required":false}, {"code":"manufacturer","label":"제조자(수입자)","required":true}, {"code":"made_in","label":"제조국","required":true}, {"code":"import_notice","label":"수입 기구·용기 신고 문구(수입품만)","required":false}, {"code":"warranty","label":"품질보증기준","required":true}, {"code":"as_contact","label":"A/S 책임자와 전화번호","required":true}]', '[]', FALSE
WHERE EXISTS (SELECT 1 FROM categories WHERE id = 9115)
ON CONFLICT (category_id) DO NOTHING;

INSERT INTO category_requirements (category_id, required_attributes, required_documents, restricted)
SELECT 9116, '[{"code":"model_name","label":"품명 및 모델명","required":true}, {"code":"certification","label":"법에 의한 인증·허가 사항(해당 시)","required":false}, {"code":"made_in","label":"제조국 또는 원산지","required":true}, {"code":"manufacturer","label":"제조자(수입자)","required":true}, {"code":"as_contact","label":"A/S 책임자와 전화번호","required":true}]', '[]', FALSE
WHERE EXISTS (SELECT 1 FROM categories WHERE id = 9116)
ON CONFLICT (category_id) DO NOTHING;

INSERT INTO category_requirements (category_id, required_attributes, required_documents, restricted)
SELECT 9117, '[{"code":"model_name","label":"품목 및 제품명","required":true}, {"code":"usage","label":"용도 및 제형","required":true}, {"code":"expiry","label":"제조연월 및 유통기한","required":true}, {"code":"capacity","label":"중량·용량·매수","required":true}, {"code":"efficacy","label":"효과·성능","required":true}, {"code":"manufacturer","label":"제조자(수입자) 및 제조국","required":true}, {"code":"child_protective","label":"어린이보호포장 대상 여부","required":true}, {"code":"chemicals","label":"제품에 사용된 화학물질 명칭","required":true}, {"code":"caution","label":"사용상 주의사항","required":true}, {"code":"safety_report_no","label":"안전기준 적합확인 신고번호 또는 승인번호","required":true}, {"code":"as_contact","label":"소비자상담 관련 전화번호","required":true}]', '[]', FALSE
WHERE EXISTS (SELECT 1 FROM categories WHERE id = 9117)
ON CONFLICT (category_id) DO NOTHING;
