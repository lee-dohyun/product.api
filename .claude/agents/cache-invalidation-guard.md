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

## What is actually cached here (verified, keep this current)

Four cache names exist, all configured in `config/RedisConfig.java`:

| cache | declared in | key | TTL | value serializer |
|---|---|---|---|---|
| `product` | `ProductService.getProduct` — `@Cacheable(cacheNames = PRODUCT_CACHE, key = "#id")` | product id | 10 min | **fixed-type** `Jackson2JsonRedisSerializer<ProductResponse>` (the `cacheDefaults`) |
| `main-best` | `MainPageService.getBestProducts` | SpEL default (the `limit` arg) | 5 min | `GenericJackson2JsonRedisSerializer` |
| `main-new` | `MainPageService.getNewProducts` | SpEL default (the `limit` arg) | 5 min | `GenericJackson2JsonRedisSerializer` |
| `main-by-category` | `MainPageService.getProductsByCategory` | SpEL default (no args) | 10 min | `GenericJackson2JsonRedisSerializer` |

Two things people assume and get wrong:

- **`MainPageService.getBanners()` is not cached.** It hits `bannerRepository` on every request and logs
  `[MainPageService/getBanners]`. Caching it is open work (product.api issue #9) — do not describe banners
  as cached, and if you add the cache, add its eviction in the same change.
- **`ProductService.listProducts` is not cached either.** Only single-product detail is.

## What to check

1. **Every write has a matching eviction.** `ProductService` evicts `product:{id}` on `updateProduct`,
   `deleteProduct`, `createVariant`, `updateVariant`, `deleteVariant`; `InventoryDeductor` evicts the same
   entry via `CacheManager` directly (it holds `CacheManager` rather than `ProductService` to avoid a
   circular dependency — keep that shape). For each changed write path, trace which cached read could now
   return stale data. A write that mutates something a cached method returns, with no eviction, is a defect.
2. **The known gap: no write path anywhere evicts `main-best` / `main-new` / `main-by-category`.**
   `grep -rn "CacheEvict\|cacheManager.getCache" src/main/java` returns nothing touching those three names.
   Every product create/update/delete, every variant change, and every inventory deduction or restoration
   leaves the main page serving pre-change price and stock until the 5- or 10-minute TTL expires. This is a
   real defect, not a design choice — say so when a write path is touched, and prefer
   `@CacheEvict(cacheNames = {"main-best","main-new","main-by-category"}, allEntries = true)` over adding
   another uncovered write. Because those caches are keyed by the `limit` argument, per-entry eviction does
   not work; `allEntries = true` is required.
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
7. **A new cache name inherits a fixed-type serializer.** `cacheDefaults` serializes values as
   `ProductResponse` and *only* `ProductResponse`. Any `@Cacheable` with a cache name not registered via
   `withCacheConfiguration(...)` in `RedisConfig` will fail to deserialize whatever else it stores. Register
   the new name explicitly (that is why the three `main-*` caches use the generic serializer).
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
