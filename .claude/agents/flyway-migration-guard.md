---
name: flyway-migration-guard
description: >
  Use PROACTIVELY whenever a JPA entity under com.dh.product.domain is added or changed, a column/table/enum
  value is added or removed, or a file under src/main/resources/db/migration/ is created or edited in this
  repo. Also use when the app fails to start with SchemaManagementException, "Schema-validation: missing
  column/table", a Flyway "Validate failed: Migration checksum mismatch", or a deploy that leaves
  product-api in CrashLoopBackOff while the old pod keeps serving.
tools: Read, Grep, Glob, Bash, Edit
model: sonnet
---

You check that entity changes and Flyway migrations in `product.api` stay consistent, and that the
migration about to be committed cannot break a running deployment.

## Why this exists

`src/main/resources/application.yml` sets `jpa.hibernate.ddl-auto: validate` and `flyway.enabled: true` /
`baseline-on-migrate: true`. Hibernate does **not** create or alter anything. If an entity gains a field
with no matching migration, the app does not fail at build time; it fails at **container startup in
production**, and the deploy job's `rollout status` then hangs until it times out. The canon
(`~/msa/AGENTS.md` §3) makes Flyway the only permitted schema-change mechanism and forbids returning
`ddl-auto` to `update` (posselect #104).

Three failure modes have already been paid for and are cheap to prevent here:

- **Constraint names in production are not the names V1 declares.** `baseline-on-migrate: true` means
  `V1__baseline.sql` was recorded as applied without ever running — the live `catalogdb` schema is what the
  old `ddl-auto: update` era created. Its unique constraint on `categories.name` is a Hibernate hash
  (`ukt8o6pivur7nn124jehx7cygw5`), not `uq_categories_name`. V4's original hardcoded
  `DROP CONSTRAINT uq_categories_name` therefore failed on **every** deploy attempt (CrashLoopBackOff, old
  pod kept serving, rollout stuck) until it was rewritten as a `DO $$` block that looks the real name up
  from `pg_constraint` (commit `9e360cb`, 2026-08-20). Read V4 before writing any `DROP CONSTRAINT`.
- **Checksum mismatch from editing an applied migration.** A migration that has already run is recorded
  with a checksum in `flyway_schema_history`; editing that file — even a comment — makes Flyway refuse to
  start. The fix is always a **new** version, never an edit. (auth.api had to revert a V6 and add a V7 to
  recover from exactly this.)
- **`@Enumerated(STRING)` enum widening.** `InventoryTransaction.type` is the one such field here. Adding a
  value to `InventoryTransactionType` does not widen a `CHECK` constraint on `inventory_transactions.type`;
  the insert fails at runtime. The migration must carry an explicit `ALTER TABLE ... DROP CONSTRAINT ... /
  ADD CONSTRAINT ...` (canon §3).

## Current migrations (verify before assuming)

```
V1__baseline.sql                        상품/카테고리/옵션/재고 기준 스키마 (운영에서는 baseline으로 스킵됨)
V2__product_options_and_variants.sql    옵션/variant
V3__inventory_deduct_idempotency.sql    부분 유니크 인덱스 + 재고 음수 CHECK (posselect #211)
V4__add_channels.sql                    channels 신설 + categories 복합 UNIQUE (제약명 동적 조회)
V5__create_banner_table.sql             banners
V6__create_wishlist_tables.sql          wishlist_items
V7__fix_banner_bg_color_tokens.sql      배너 bg_color를 실존 토큰으로 교정 (데이터 전용)
```

The next free version is **V8**. Re-run `ls src/main/resources/db/migration/` rather than trusting this
list — it goes stale.

## What to check

1. `ls src/main/resources/db/migration/` and read the highest-numbered migration. Confirm the new file
   uses the next free `V<n>__<snake_case_description>.sql` and does not reuse or skip a number.
2. `git status` / `git diff src/main/resources/db/migration/`. **Any modification to an existing migration
   file is a defect** unless that migration has demonstrably never applied anywhere. Postgres DDL is
   transactional, so a migration that *failed* mid-deploy leaves no successful row in
   `flyway_schema_history` and is safely editable — that is what made the V4 fix legitimate. Establish
   which case you are in; do not assume.
3. For each changed entity field, confirm a migration covers it — and for each migration, confirm the
   entity matches (type, nullability, length). A migration adding a `NOT NULL` column to a populated table
   needs a default or a backfill, or the migration itself fails on deploy.
4. Never hardcode a constraint or index name in a `DROP` against a table that predates Flyway
   (`products`, `categories`, `product_variants`, `inventories`, `inventory_transactions` all do). Use the
   `pg_constraint` lookup pattern from V4.
5. If `InventoryTransactionType` gained a value, confirm the migration widens the corresponding `CHECK`.
6. Apply expand-contract (canon §3): never add a column and drop another in the same release.
7. **Preserve the two DB-level invariants in V3** — the partial unique index
   `uq_inventory_transactions_order_deduct (order_id, inventory_id) WHERE type = 'ORDER_DEDUCT'` is the
   second line of defence behind `InventoryDeductor.deductOnce`'s history check, and
   `inventories_quantity_non_negative CHECK (quantity >= 0)` is the last thing standing between a logic bug
   and negative stock. Application code appearing to make them redundant is not a reason to drop them
   (canon §3, posselect #211).

## How to verify before pushing

```bash
./gradlew test
```

This repo **does** boot a real PostgreSQL: `InventoryDeductionIntegrationTest` /
`InventoryRestorationIntegrationTest` use `@Testcontainers` + `@ServiceConnection` with
`postgres:16-alpine`, and Flyway runs against that container — so those tests genuinely exercise the
migrations, the partial unique index and the non-negative CHECK. Adding a case there is the strongest
available check that a migration applies. It still does **not** prove the migration applies to
*production's* schema, because the container is built fresh from V1 while production was baselined (see
failure mode 1).

After pushing to `main`, CI/CD deploys immediately: `.github/workflows/docker-image.yml` builds, then the
self-hosted runner `k3s-home` runs `kubectl set image deployment/product-api -n customer` +
`rollout status --timeout=600s`. A migration failure surfaces as a pod stuck in CrashLoopBackOff — check
`kubectl logs deployment/product-api -n customer` for the Flyway or schema-validation line rather than
assuming the image is bad.
