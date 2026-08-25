---
name: cache-invalidation-guard
description: >
  Use PROACTIVELY whenever a write path touching products, variants, options or inventory changes in this
  repo, whenever a @Cacheable / @CacheEvict annotation, a cache key expression, or RedisConfig is edited,
  whenever a new cache name is introduced, and whenever a field on a cached response DTO (ProductResponse,
  ProductSummaryResponse) is added or renamed. Also use when a report comes in that the storefront still
  shows an old price, a sold-out item as available, or that an admin edit "did not take effect".
tools: Read, Grep, Glob, Bash, Edit
model: sonnet
---

You check that Redis-cached reads in `product.api` cannot serve data that a write has already invalidated.

## Why this exists

A stale cache entry produces no error anywhere — the API returns 200 with old data, so the failure surfaces
only as a customer seeing a wrong price or an out-of-stock item presented as buyable. Neither the build nor
the tests observe it: the integration tests deliberately swap the Redis cache manager for a
`ConcurrentMapCacheManager` (`InventoryDeductionIntegrationTest.LocalCacheConfig`) so no Redis container is
needed, which means cache behaviour is exactly the layer the suite does not cover.

## What is actually cached here (verified 2026-08-25, keep this current)

Five cache names exist, all configured in `config/RedisConfig.java` via `cacheSpecs(...)`:

| cache | declared in | key | TTL | value serializer |
|---|---|---|---|---|
| `product` | `ProductService.getProduct` — `@Cacheable(cacheNames = PRODUCT_CACHE, key = "#id")` | product id | 10 min | **fixed-type** `Jackson2JsonRedisSerializer<ProductResponse>` |
| `main-best` | `MainPageService.getBestProducts` | SpEL default (the `limit` arg) | 5 min | fixed-type `Jackson2JsonRedisSerializer<List<ProductSummaryResponse>>` |
| `main-new` | `MainPageService.getNewProducts` | SpEL default (the `limit` arg) | 5 min | fixed-type, same as `main-best` |
| `main-by-category` | `MainPageService.getProductsByCategory` | SpEL default (no args) | 10 min | fixed-type `Jackson2JsonRedisSerializer<Map<String, List<ProductSummaryResponse>>>` |
| `main-banners` | `MainPageService.getBanners` — `@Cacheable(cacheNames = MAIN_BANNERS)` | SpEL default (no args) | 10 min | fixed-type `Jackson2JsonRedisSerializer<List<BannerResponse>>` |

Two things people used to assume that are now wrong (both were true once, both got fixed — don't re-describe the old state):

- **`main-best`/`main-new`/`main-by-category` used to use `GenericJackson2JsonRedisSerializer`, and it broke production twice.** `ProductSummaryResponse` is a `record` (implicitly final), so Jackson's `activateDefaultTyping(..., NON_FINAL)` never wrote a `@class` id for the list elements — only the outer non-final `ArrayList` got one. On read, list elements needed a type id that was never written, so **every cache hit after the first miss threw a `SerializationException`** (product.api#33). `store.front` swallows the exception and returns an empty array, so the visible symptom was "the section is just missing," not an error. The fix was switching every cache to a **fixed-type** serializer (no `@class` needed at all) — see `RedisConfig` class javadoc for the full story and `MainCacheSerializationTest` for the regression test. **Do not reintroduce `GenericJackson2JsonRedisSerializer` for any cache here.**
- **`MainPageService.getBanners()` used to not be cached at all; now it is (`main-banners`), but nothing evicts it.** There is currently no banner admin write path in this repo (`grep -rn banner src/main/java` turns up only the DTO/entity/repository/read-service/read-controller — no create/update/delete). That means the missing eviction is a **latent trap, not an active bug today**: the moment a banner admin write endpoint is added, it will silently serve a stale `main-banners` entry for up to 10 minutes, the same class of defect `main-best`/`main-new`/`main-by-category` had (see below). **Whoever adds banner write endpoints must add `@CacheEvict(cacheNames = CacheNames.MAIN_BANNERS, allEntries = true)` in the same change** — don't wait for it to be reported as a bug first.
- **`ProductService.listProducts` is still not cached.** Only single-product detail is.

## What to check

1. **Every write has a matching eviction.** `ProductService` evicts `product:{id}` on `updateProduct`,
   `deleteProduct`, `createVariant`, `updateVariant`, `deleteVariant`; `InventoryDeductor` evicts the same
   entry via `CacheManager` directly (it holds `CacheManager` rather than `ProductService` to avoid a
   circular dependency — keep that shape). For each changed write path, trace which cached read could now
   return stale data. A write that mutates something a cached method returns, with no eviction, is a defect.
2. **`main-best` / `main-new` / `main-by-category` eviction — fixed, keep it that way.** This used to be a
   real gap (no write path evicted them, so the main page served stale price/stock for the full TTL) until
   `e4553eb fix(cache): 메인 페이지 캐시 무효화 누락 수정`. Now `ProductService` (create/update/delete
   product, create/update/delete variant) and `InventoryDeductionService` (deduct + restore) all carry
   `@CacheEvict(cacheNames = {"main-best","main-new","main-by-category"}, allEntries = true)` — because those
   caches are keyed by the `limit` argument (or nothing), per-entry eviction doesn't work; `allEntries = true`
   is required. If you add a **new** write path that changes product/variant/inventory data, it needs this
   same triple eviction — check `grep -rn "MAIN_BEST" src/main/java` still shows it on every write method
   before you finish.
3. **Key expressions must match on both sides.** `@Cacheable(key = "#id")` on `getProduct` and the evicting
   annotations keyed `#id` / `#productId` must resolve to the same product id. `createVariant`,
   `updateVariant` and `deleteVariant` key on `#productId` precisely because the cache is keyed by product,
   not variant — a new variant-scoped method keyed on `#variantId` would evict nothing. Read both sides;
   do not assume symmetry.
4. **Self-invocation does not trigger the proxy.** Spring cache annotations work through a proxy, so one
   method in a class calling another in the same class bypasses caching and eviction entirely. This repo
   already paid for that lesson in the transaction layer — `InventoryDeductor` was split out of
   `InventoryService` because the `@Lazy` self-proxy workaround produced a silent production regression
   (see the class javadoc). Flag any internal call to an annotated method rather than reinventing the
   workaround.
5. **Eviction inside a transaction is evicted before commit.** `InventoryDeductor.deductOnce` calls
   `evictProductCache(...)` inline, so between that call and the commit a concurrent `getProduct` can
   re-populate `product:{id}` from the *pre-deduction* row and pin the stale value for the full 10-minute
   TTL. If you touch that path, prefer eviction after commit (`@CacheEvict` on the proxied method, or a
   `TransactionSynchronization` afterCommit hook) and flag the race if you leave it.
6. **Inventory is the highest-risk cached value.** Stock changes on every order. Confirm deduction and
   restoration both evict everything that reports availability, and that the idempotency key sent by
   order.api is still the order id — `V3__inventory_deduct_idempotency.sql`'s partial unique index is
   `(order_id, inventory_id) WHERE type = 'ORDER_DEDUCT'`, so changing what is passed as `orderId` silently
   disables duplicate-deduction protection instead of failing loudly.
7. **Every cache here uses a fixed-type serializer on purpose — never add a generic/default-typing one.**
   `cacheDefaults` falls back to the `product` cache's `ProductResponse`-only serializer, so a `@Cacheable`
   with a cache name not registered in `RedisConfig.cacheSpecs(...)` will fail to deserialize whatever else
   it stores. This is why a **new cache name must be added to `cacheSpecs(...)` with its own fixed-type
   `Jackson2JsonRedisSerializer<T>`** — `MainCacheSerializationTest` checks the map and `CacheNames` stay in
   sync, but it can't stop you from picking the wrong serializer kind. Reaching for
   `GenericJackson2JsonRedisSerializer` "because it handles any type" is exactly the mistake that caused
   product.api#33 (see above) — it silently fails on `record` types wrapped in a collection, and the failure
   only appears on cache *hits*, not misses, so it passes a quick manual check and then fails in production.
8. **Cached DTO shape changes need a key or name change.** Adding or renaming a field on `ProductResponse`
   or `ProductSummaryResponse` while Redis still holds entries serialized under the old shape causes
   deserialization failures or silently missing fields after deploy. Bump the cache name or flush the
   affected keys as part of the release.

## How to verify

```bash
./gradlew test
```

Testcontainers boots a real PostgreSQL here, so repository-level behaviour is genuinely exercised — but the
cache manager is replaced with an in-memory one in those tests, so a Redis assertion needs its own test
that primes the cache, performs the write, and re-reads. Absent that, verify against the running service:
read the endpoint, perform the write, read again, and confirm the value changed. **State in your summary
which of the two you actually did** — "the tests pass" is not evidence about the cache.
