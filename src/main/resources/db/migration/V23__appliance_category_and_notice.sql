-- =========================================================
-- product.api#81 후속 (2026-09-30 사용자 요청): 가전디지털(9004) 아래 「생활가전」 소분류 + 고시 요건.
--
-- 1) 카테고리: 운영에는 같은 날 먼저 직접 만들어 두었다(id 9127). 여기서는 없을 때만 만든다 -
--    새로 만든 DB(테스트·재구축)에서도 같은 구조가 나오게 하려는 것. id 는 identity 에 맡기고
--    이름+부모로 찾는다.
-- 2) 고시: 「정보제공 고시」의 「가정용 전기제품」 품목군(청소기 등). 에너지소비효율등급은 대상 품목만
--    해당하므로 required=false. KC 인증은 판매 정책(kcCertType)에도 있지만 고시 표시 항목이라 따로 둔다.
--    인허가 품목군이 아니므로 restricted = FALSE.
-- =========================================================
INSERT INTO categories (name, parent_id, channel_id, is_demo, sort_order)
SELECT '생활가전', 9004, c.channel_id, FALSE, 4
FROM categories c
WHERE c.id = 9004
  AND NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = 9004 AND x.name = '생활가전');

INSERT INTO category_requirements (category_id, required_attributes, required_documents, restricted)
SELECT c.id, '[{"code":"model_name","label":"품명 및 모델명","required":true}, {"code":"certification","label":"KC 인증정보(전기용품 안전인증 등)","required":true}, {"code":"rated_power","label":"정격전압·소비전력","required":true}, {"code":"energy_grade","label":"에너지소비효율등급(해당 시)","required":false}, {"code":"release_date","label":"동일모델의 출시년월","required":true}, {"code":"manufacturer","label":"제조자(수입자)","required":true}, {"code":"made_in","label":"제조국","required":true}, {"code":"size","label":"크기(용량·형태 포함)","required":true}, {"code":"warranty","label":"품질보증기준","required":true}, {"code":"as_contact","label":"A/S 책임자와 전화번호","required":true}]', '[]', FALSE
FROM categories c
WHERE c.parent_id = 9004 AND c.name = '생활가전'
ON CONFLICT (category_id) DO NOTHING;
