# product.api AI 개발 지침

> **캐논 참조**: 이 저장소의 공통 개발 원칙(DB/트랜잭션/보안/배포 규칙 등)은 `~/msa/AGENTS.md`를 우선 따른다.
> 원칙이 충돌하면 캐논이 이긴다. 아래는 이 저장소만의 특이사항이다.
>
> `CLAUDE.md`는 이 파일(`AGENTS.md`)로의 심링크다 — 둘 중 아무거나 고쳐도 같은 파일이다.

## 이 저장소는 무엇인가

`product.api`는 PosSelect 쇼핑몰의 **카탈로그·재고 도메인** 서비스다. Spring Boot 3.5.3 / Java 21 / Gradle.
Postgres(`catalogdb`) + Redis를 쓴다.

담당 범위는 상품·카테고리(채널별 계층), 옵션/variant(SKU), 재고와 재고 이력, 장바구니, 찜(위시리스트),
메인페이지 배너다. **주문·결제는 이 저장소에 없다**(order.api). 대신 order.api가 결제 확정 시점에
클러스터 내부망으로 이 서비스의 `/internal/**`을 호출해 가격을 확정받고 재고를 차감한다.

## 명령어

```bash
./gradlew build          # 컴파일 + 테스트 + build/libs/*.jar
./gradlew test           # 테스트만 (JUnit 5 + Testcontainers)
./gradlew compileJava    # 컴파일만 (빠른 확인용)
./gradlew test --tests com.dh.product.service.InventoryDeductionIntegrationTest
./gradlew bootRun        # 로컬 실행 (Postgres + Redis 필요)
```

푸시 전에 `./gradlew test`를 로컬에서 직접 돌려 성공을 확인한다. CI 에러에만 의존하지 말 것.
`.claude/hooks/pre-push-verify.sh`가 `git push` 직전에 이 검사를 강제한다(정당한 사유가 있으면
`CLAUDE_SKIP_PUSH_VERIFY=1`).

**Node/npm 명령은 이 저장소에 없다.** 프론트엔드 저장소의 `npm run typecheck` / `npm run test` 같은 지시를
이 저장소에 적용하지 말 것 — 여기서 대응하는 검증은 전부 `./gradlew`다.

CI/CD(`.github/workflows/docker-image.yml`): `main` push → `./gradlew build` → Docker Hub 푸시 → Trivy 스캔 →
self-hosted runner(`k3s-home`)가 `kubectl set image deployment/product-api -n customer`로 **즉시 프로덕션
반영**(`rollout status --timeout=600s`). 문서/설정만 바꾸는 커밋은 커밋 메시지 끝에 `[skip ci]`를 붙인다.

## 서브에이전트 가드 (`.claude/agents/`)

이 저장소에는 실제 사고에서 뽑은 점검 두 개가 서브에이전트로 들어 있다. 해당 상황이면 **먼저 돌리고**
그 결과를 커밋 전 검수(캐논 §4-4)로 삼는다.

- **`flyway-migration-guard`** — `src/main/resources/db/migration/` 아래 파일을 만들거나 고칠 때,
  `com.dh.product.domain` 엔티티에 필드/테이블/enum 값이 늘거나 줄 때, 부팅이
  `Schema-validation` / `Migration checksum mismatch`로 실패할 때, 배포가 CrashLoopBackOff로 멈출 때.
- **`cache-invalidation-guard`** — 상품/variant/옵션/재고 쓰기 경로를 건드릴 때,
  `@Cacheable`/`@CacheEvict`/키 표현식/`RedisConfig`를 고칠 때, 캐시되는 DTO 필드가 바뀔 때,
  "관리자에서 고쳤는데 화면이 그대로"라는 제보가 올 때.

## 작업 기록 (GitHub)

캐논 §4를 따른다. 착수 전에 GitHub Project #2("PosSelect 쇼핑몰 웹 애플리케이션 구축")와 이 저장소의
Issue를 조회해 겹치는 작업이 이미 `In Progress`인지 확인하고, 없으면 **코드를 건드리기 전에** 이슈를
`In Progress`로 선점한다(여러 AI 도구 세션이 동시에 도는 환경이다).

- **저장소에 연결되지 않은 Draft issue를 만들지 말 것.** 반드시 `gh issue create -R lee-dohyun/product.api`로
  실제 저장소 Issue를 만든 뒤 `gh project item-add 2 --owner lee-dohyun --url <issue-url>`로 Project #2에
  연결한다. Draft 카드는 저장소·커밋·PR과 이어지지 않아 추적이 끊기고, 과거 중복 카드 210여 건이 쌓인
  원인이 이것이었다.
- 작업이 끝나면 커밋 메시지의 `Closes #<번호>` 또는 `gh issue close`로 반드시 닫는다.
- `gh`는 `~/.local/bin/gh` 풀 경로로 호출한다.

## 스키마 변경은 Flyway로만

- 운영 설정은 `ddl-auto: validate` + `flyway.enabled: true`(`baseline-on-migrate: true`)다.
  **`ddl-auto: update`로 되돌리지 말 것**(캐논: posselect #104).
- 마이그레이션은 `src/main/resources/db/migration/`에 **V1~V7이 실재한다.** V1 기준 스키마,
  V2 옵션/variant, V3 재고 차감 멱등성(+음수 재고 CHECK), V4 채널, V5 배너, V6 찜, V7 배너 색상 토큰 교정.
  다음 번호는 V8.
- **이미 배포된 마이그레이션 파일은 수정하지 않는다** — Flyway checksum 불일치로 부팅이 막힌다.
  잘못 나간 건 되돌리지 말고 뒤에 새 버전을 덧붙여 고친다.
- **`baseline-on-migrate: true`라 운영 DB에서 V1은 실행된 적이 없다.** 운영 `catalogdb`의 스키마는
  예전 `ddl-auto: update` 시절이 만든 것이고, 그래서 제약 이름이 V1의 SQL이 붙일 이름이 아니라
  Hibernate 해시(`ukt8o6pivur7nn124jehx7cygw5`)다. V4가 `DROP CONSTRAINT uq_categories_name`을
  하드코딩했다가 **모든 배포 시도가 CrashLoopBackOff로 막혔다**(2026-08-20, 커밋 `9e360cb`).
  지금 V4는 `pg_constraint`에서 실제 이름을 찾아 지우는 `DO $$` 블록이다 — 제약을 지울 일이 생기면
  이 패턴을 복사할 것.
- `InventoryTransaction.type`이 이 저장소 유일한 `@Enumerated(STRING)` 필드다. enum에 값을 추가하면
  CHECK 제약은 자동으로 안 넓혀지므로 마이그레이션에 `ALTER`를 포함한다.
- **V3의 DB 제약 두 개를 지우지 말 것**: 부분 유니크 인덱스
  `uq_inventory_transactions_order_deduct (order_id, inventory_id) WHERE type = 'ORDER_DEDUCT'`와
  `inventories_quantity_non_negative CHECK (quantity >= 0)`. 응용 로직이 중복돼 보여도 그게 마지막
  방어선이다(캐논 §3, posselect #211).

## Redis 캐시 — 무효화가 자동이 아니다

캐시 이름은 4개이고 전부 `config/RedisConfig.java`에 있다.

| 캐시 | 선언 | 키 | TTL |
| --- | --- | --- | --- |
| `product` | `ProductService.getProduct` | `#id` | 10분 |
| `main-best` | `MainPageService.getBestProducts` | 인자(`limit`) | 5분 |
| `main-new` | `MainPageService.getNewProducts` | 인자(`limit`) | 5분 |
| `main-by-category` | `MainPageService.getProductsByCategory` | 인자 없음 | 10분 |

- **`main-*` 세 캐시를 무효화하는 코드는 저장소 어디에도 없다.** 상품 생성/수정/삭제, variant 변경,
  재고 차감·복원 전부 메인 페이지에는 TTL(5~10분)이 지나야 반영된다. 이건 설계가 아니라 알려진 결함이니
  해당 쓰기 경로를 건드리면 `@CacheEvict(cacheNames = {"main-best","main-new","main-by-category"},
  allEntries = true)`를 같이 넣는 쪽을 우선 검토한다(인자 기반 키라 개별 eviction은 안 통한다).
- `product` 캐시는 `ProductService`의 update/delete/variant 계열이 `#id`/`#productId`로 evict하고,
  재고 쪽은 `InventoryDeductor`가 `CacheManager`로 직접 evict한다(`ProductService`를 참조하면 순환
  의존이라 일부러 이렇게 뒀다).
- `cacheDefaults`의 값 직렬화기는 **`ProductResponse` 고정 타입**이다. `RedisConfig`에
  `withCacheConfiguration(...)`으로 등록하지 않은 새 캐시 이름을 `@Cacheable`에 쓰면 다른 타입을 담는
  순간 역직렬화가 깨진다. `main-*` 세 개가 제네릭 직렬화기를 따로 쓰는 이유가 이것이다.
- **배너는 캐시되지 않는다.** `MainPageService.getBanners()`는 매 요청 DB를 친다(캐싱은 미착수, 이 저장소
  Issue #9). "배너 캐시" 전제로 코드를 읽지 말 것.
- 캐시되는 DTO(`ProductResponse`, `ProductSummaryResponse`)에 필드를 추가/개명하면 이전 모양으로
  직렬화된 기존 엔트리가 남아 배포 직후 깨진다 — 캐시 이름을 올리거나 키를 비우는 걸 릴리스에 포함한다.

## 트랜잭션 — 우회하지 말고 클래스를 분리한다

`ProductService` / `MainPageService` / `CartService`는 클래스 레벨 `@Transactional(readOnly = true)`다.
여기에 JPA 쓰기 경로를 추가하면 UPDATE가 조용히 사라진다(캐논 §3).

- 재고 쓰기 경로는 **일부러 별도 빈으로 분리돼 있다.** 1차 시도(`dac4437`)는 `InventoryService`
  (클래스 레벨 readOnly) 안에서 전파 속성 + `@Lazy` 자기 프록시로 우회했다가, 운영에서 "이력 INSERT는
  되는데 차감 UPDATE만 사라지는" 회귀가 나서 롤백했다. 지금은 `InventoryDeductor`가 트랜잭션 경계를
  전담한다 — 여기에 클래스 레벨 `readOnly`를 붙이거나 자기 프록시를 되살리지 말 것
  (`InventoryDeductor` 클래스 주석에 전말이 있다).
- **`InventoryDeductionService`에는 `@Transactional`을 붙이면 안 된다.** 이 클래스의 catch가
  `DataIntegrityViolationException`(동시 중복 차감을 유니크 인덱스가 막은 경우)을 성공으로 바꿔주는데,
  트랜잭션 안에서 잡으면 이미 rollback-only라 뒤따르는 잔고 조회까지 같이 죽는다.
- `CartService`도 클래스 레벨 `readOnly`지만 쓰기가 **Redis**(`cart:{cartId}`, TTL 30일,
  `StringRedisTemplate`)로 나가서 걸리지 않는다. 장바구니는 Postgres에 없다 — 백업·마이그레이션 논의에서
  빠지기 쉬우니 유의.
- `WishlistService.addWishlist`의 중복 검사는 read-then-write라 동시 요청에 새는데, V6의
  `uq_wishlist_user_product` 유니크 제약이 뒤엣것을 막는다. 제약을 지우면 방어가 사라진다.

## 신뢰 경계

- **`/internal/**`은 게이트웨이에 라우트가 없어 외부에서 도달 불가능하다.** `InternalVariantController`
  (가격 확정, posselect #232)와 `InventoryController`(차감/복원)가 여기 있고, 그래서 별도 인증이 없다.
  이 프리픽스에 엔드포인트를 추가할 때는 "내부망 전용"이라는 전제가 계속 성립하는지 확인하고, 반대로
  외부에 노출할 것을 실수로 이 아래에 두지 말 것.
- **관리자 쓰기 인증은 `AdminAuthInterceptor`가 `/api/products/**`와 `/api/categories/**`의 non-GET에만
  건다.** Keycloak `staff` realm 토큰을 `AdminJwtVerifier`로 직접 검증한다(게이트웨이 헤더를 믿지 않는다).
  새 관리자용 쓰기 엔드포인트를 이 두 프리픽스 **밖에** 만들면 인증이 아예 안 걸린다 —
  `WebConfig.addInterceptors`의 패턴을 같이 늘려야 한다.
- 찜(`/api/wishlists`)은 게이트웨이가 주입하는 `X-User-Id`(Keycloak sub)를 그대로 신뢰한다. 게이트웨이를
  거치지 않고 노출되면 그대로 위조가 되므로, 이 서비스를 게이트웨이 뒤가 아닌 곳에 노출하지 말 것
  (msa #87). 소유자 키로 이메일을 쓰지 말 것(캐논 §3, posselect #210).
- 장바구니(`/api/cart`)는 `CART_ID` 쿠키로 식별한다. `WebConfig.addCorsMappings`가 `/api/cart/**`는
  자격증명 포함, `/api/categories/**`는 자격증명 없이 허용하는데, posselect-shell의 Header/Footer 위젯이
  다른 서브도메인 페이지에서 이 API를 브라우저로 직접 부르기 때문이다.
- 로그인 전에 호출돼야 하는 경로를 추가하면 `gateway`의 `PUBLIC_EXACT_PATHS` / `PUBLIC_PATH_PREFIXES`에도
  **반드시 같이** 등록해야 한다. 이 저장소의 라우팅만으로는 아무 효력이 없다(캐논 §3).

## 테스트가 무엇을 증명하고 무엇을 못 하는가

`InventoryDeductionIntegrationTest` / `InventoryRestorationIntegrationTest`는 `@Testcontainers` +
`@ServiceConnection`으로 **진짜 Postgres(`postgres:16-alpine`)** 를 띄우고 Flyway를 태운 뒤,
서비스 반환값이 아니라 `JdbcTemplate`으로 **커밋된 행**을 읽어 검증한다. 트랜잭션 전파·멱등성 변경은
이 방식으로만 검증이 성립한다(캐논 §3, posselect #211). 목 기반 단위 테스트만으로 "멱등하다"고 쓰지 말 것.

다만 그 테스트들은 Redis 컨테이너를 피하려고 캐시 매니저를 `ConcurrentMapCacheManager`로 갈아끼운다
(`LocalCacheConfig`). **즉 캐시 동작은 테스트가 덮지 않는 층이다** — 캐시를 건드렸다면 엔드포인트를
직접 읽고, 쓰고, 다시 읽어 확인하고 무엇을 했는지 명시한다.

## 관련 서비스

- [gateway](../gateway) — 단일 진입점. JWT 검증 후 `X-User-*` 주입, 클라이언트가 보낸 동명 헤더는 제거.
- [order.api](../order.api) — `/internal/variants/resolve`(가격 확정)와 `/internal/inventory/deduct|restore`의
  유일한 호출자. 이 API의 계약을 바꾸면 저쪽 `ProductApiClient`를 같이 고쳐야 한다.
- [store.front](../store.front) — `home.posselect.com`의 메인/상품 화면. 배너 `bg_color`처럼 DB 값이
  프론트의 CSS 토큰과 맞아야 하는 지점이 있다(V7 사고).
- [admin.front](../admin.front) — 상품/카테고리 쓰기의 유일한 정상 경로(staff realm 토큰).

## Testcontainers 실행 (이 개발 머신 한정)

`/var/run/docker.sock` 이 root 전용 podman 소켓을 가리키고 있어, 기본 설정으로는 통합 테스트가
전부 `DockerClientProviderStrategy` 오류로 실패한다. 코드 문제가 아니므로 소켓만 바꿔주면 된다:

```bash
DOCKER_HOST="unix:///run/user/1000/podman/podman.sock" TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test
```

CI(ubuntu-latest)는 실제 Docker 가 있어 그대로 동작한다. 로컬에서 통합 테스트가 안 돈다고
"환경 탓"으로 넘기지 말 것 — 이 저장소의 정합성 검증은 대부분 Testcontainers 위에 있다.
