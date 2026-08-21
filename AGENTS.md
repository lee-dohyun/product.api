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

---

<!-- canon:begin sha=bbe920c40436 src=~/msa/AGENTS.md -->
## 공통 캐논 (모든 AI 도구 공통)

> **공통 캐논 (자동 주입 — 손으로 고치지 말 것).** 원본은 `~/msa/AGENTS.md`이고 이 블록은
> `~/msa/scripts/sync-agents-canon.sh`가 넣는다. 이 저장소만 클론해 도는 도구(Codex, CI,
> 워크스페이스를 저장소로만 연 IDE)는 `~/msa`를 볼 수 없으므로 규칙을 여기 함께 둔다.
> **규칙을 바꿀 때는 원본을 고치고 sync 스크립트를 다시 돌릴 것.**

### 현재 단계: 개발 단계 (운영 제약 유예)

**posselect는 아직 실사용자 트래픽이 없는 개발 단계다.** 사용자가 명시적으로 확인한 사항: 무중단 배포·롤링 안전성·하위 호환 유지 같은 운영 제약을 기본값으로 깔지 말고, 다운타임이 나거나 기존 데이터를 리셋해야 해도 **가장 단순한 방법으로 바로 변경·적용**한다.

- 아래 §3의 **expand-contract(2단계 제거) 규칙은 이 유예가 끝난 뒤 적용**한다. 개발 단계에서는 컬럼/테이블을 한 번에 갈아엎어도 된다. 단 **Flyway 마이그레이션으로만 바꾼다는 규칙 자체는 유예 대상이 아니다**(체크섬 사고 이력).
- 이 유예는 한시적이다. **실 서비스 시작 시점은 사용자가 별도로 통지**하며, 통지 이후에는 이 절을 삭제하고 §3을 그대로 적용한다.

## 3. 불변 개발 규칙 (위반 금지)

실제 사고에서 도출된 규칙이다. 근거 이슈를 함께 표기한다.

### DB / 스키마
- **스키마 변경은 Flyway 마이그레이션으로만.** `ddl-auto`는 `validate` 유지, `update` 복귀 금지 (posselect #104).
- 스키마 변경은 **expand-contract**: 컬럼/테이블 제거는 "새것 추가 → 코드 전환 → 다음 릴리스에서 제거" 2단계로.
- `@Enumerated(STRING)` enum에 값 추가 시 기존 CHECK 제약은 자동으로 안 넓혀짐 — 마이그레이션에 `ALTER` 포함할 것.
- 재고 음수 방지 CHECK, 멱등성 유니크 인덱스 등 **DB 레벨 제약은 애플리케이션 로직과 별개로 유지**한다 (posselect #211 V3).

### 트랜잭션 / 정합성
- **`@Transactional` 안에서 원격 HTTP 호출 금지**(보상 로직 없이). 로컬 롤백돼도 원격은 롤백 안 된다 (posselect #140, order.api 사례).
- **모든 상태 변경(쓰기) API는 멱등해야 한다.** 재시도/중복 호출이 이중 차감·이중 결제가 되지 않게 멱등성 키(예: orderId) 기반 dedup을 넣는다 (posselect #211).
- 클래스 레벨 `@Transactional(readOnly = true)`인 클래스에 쓰기 경로 추가 금지 — 전파 함정으로 UPDATE가 조용히 사라진다. 쓰기는 별도 클래스 또는 `REQUIRES_NEW` (posselect #211 롤백 사례).
- **트랜잭션 전파·멱등성 변경은 단위 테스트로 검증이 성립하지 않는다.** 실제 DB 상태 변화 실측(같은 키로 2회 호출 → 1회만 반영)으로 검증하고, 실측 후 데이터 원복까지 한 세트로 수행 (posselect #211).

### 보안 / 인가
- **사용자 식별 키는 Keycloak sub(`X-User-Id`)만.** 이메일은 변경 가능하므로 소유자 키로 쓰지 않는다 (posselect #210).
- 게이트웨이 주입 헤더(`X-User-*`)는 게이트웨이가 항상 **덮어써야** 한다 — 클라이언트가 보낸 값을 통과시키면 인증 우회가 된다 (msa #87).
- **리소스 조회/변경 API에는 소유자 검사 필수.** 소유자 불일치는 403이 아니라 **404**로 응답(순번 ID에서 403은 유효 ID 범위를 노출) (posselect #214).
- **새로 외부에 노출되는 리소스는 순번 PK(BIGSERIAL)를 URL/응답에 노출하지 말 것** — public_id(UUIDv7/ULID) 별도 부여 (posselect #214 재발 방지).
- 로그인 전 호출되는 경로를 추가하면 gateway `PUBLIC_EXACT_PATHS`에도 **반드시 같이** 등록 (라우팅과 인증 화이트리스트가 다른 저장소에 있음).
- 의존성 보안 패치(특히 Next.js/Spring)는 미루지 않는다 — store-front가 Next.js RCE(CVE-2025-66478)로 실제 침해 정황을 겪음 (msa #155).

### K8s / 배포
- stateful Deployment(PVC 사용)는 `strategy: Recreate`. 모든 PV는 `reclaimPolicy: Retain`. apply 전 `claimName`을 `kubectl get pvc`와 대조.
- 새 도메인은 기존 와일드카드 TLS 시크릿을 참조만 할 것 — Ingress에 `cert-manager.io/cluster-issuer` 어노테이션 추가 금지(와일드카드 인증서를 덮어쓰는 사고 이력).
- Ingress는 `leedohyun-com-ingress.yaml`/`posselect-com-ingress.yaml` 두 파일에 host만 추가. 서비스별 개별 Ingress 금지.
- CI는 main push → Docker 이미지 → CD(self-hosted runner) 즉시 프로덕션 반영. **문서만 바꿀 땐 커밋 메시지에 `[skip ci]`.**
- 여러 서비스에 걸친 변경은 **배포 순서**를 먼저 설계할 것(예: gateway → front → api 순서를 지켜야 게스트 결제가 안 끊기는 사례, posselect #210).
- `@posselect/ui` 변경은 Storybook만 자동 배포됨 — 소비 저장소 5개(customer/store/product/admin.front + posselect-shell)를 각각 재빌드해야 화면에 반영 (posselect #197).
- **`[skip ci]`는 커밋 제목뿐 아니라 본문에서도 인식된다.** 다른 커밋을 인용하려고 본문에 그 문자열을 적으면 배포가 조용히 건너뛰어진다 — 실제로 product.api 캐시 수정이 이 때문에 배포되지 않았다(gateway#204).
- **`[skip ci]`로 건너뛴 배포를 되살릴 때**: `docker-image.yml`에 `workflow_dispatch`만 추가하면 부족하다. `deploy` 잡의 `if:`가 `github.event_name == 'push'`로 고정돼 있어 수동 실행은 빌드만 하고 배포는 skip된다. 조건도 `push || workflow_dispatch`로 함께 풀 것(현재 product.api만 적용됨).
- **`pull_request` 워크플로는 PR head 브랜치의 파일로 돈다.** main의 워크플로를 고쳐도 이미 열려 있는 PR에는 반영되지 않고, `gh run rerun`은 원래 런의 워크플로 버전을 재사용한다. 수정 확인은 **브랜치를 리베이스한 뒤** 새 런으로 할 것.
- **Dependabot PR에는 저장소 시크릿이 전달되지 않는다.** 시크릿을 쓰는 스텝(`docker/login-action`)은 `if: github.event_name == 'push'`로 막고, `secrets.X`를 문자열에 끼워 넣는 곳(이미지 태그)은 `${{ secrets.X || 'ci-local' }}` 폴백을 줄 것 — 안 그러면 모든 Dependabot PR이 상시 실패해 PR 게이트 신호가 죽는다(gateway#209).

### CLI / 스크립팅
- **SSH를 통한 원격 bash 명령 실행 시 따옴표 이스케이프 주의:** PowerShell에서 변수(`$BODY`)를 따옴표 안에 넣어 원격 `curl` 등을 호출하면 bash 쪽에서 JSON 포맷 에러(`400 Bad Request` 등)가 발생하기 쉽다. 복잡한 인용부호(JSON 등)가 포함된 스크립트는 **전체를 Base64로 인코딩한 뒤 원격에서 디코딩하여 `bash`로 실행**한다 (`echo $b64 | base64 -d | bash`).

## 4. 작업 기록 및 관리 (GitHub & Memory) — 모든 도구 공통

모든 에이전트는 더 이상 Redmine을 사용하지 않으며, 아래의 **Task Execution Workflow**에 따라 GitHub Projects 및 Issues를 단일 소스(SSOT)로 활용합니다.

1. **명령 인식 (Command Recognition)**: 사용자의 의도와 작업 범위를 명확히 파악합니다.
2. **깃허브 이슈 확인 및 즉시 선점 (Check & Claim)**: 작업을 시작하기 전에 반드시 GitHub Project #2와 관련 저장소 이슈를 조회하여 동일/겹치는 작업이 이미 `In Progress`인지 확인합니다. 조회·클레임은 `~/msa/scripts/claim.sh <repo> <issue>` 한 줄로 수행한다(다른 세션이 잡고 있으면 스크립트가 막는다). 겹치는 항목이 없으면 **코드를 건드리기 전에** 해당 이슈를 만들거나 열어 Status를 `In Progress`로 즉시 전환합니다. **이 서버는 Claude Code/Codex/Antigravity 등 여러 AI 도구를 여러 세션으로 동시에 띄워 작업하는 환경이므로, "조회만 하고 착수 시점에 클레임하지 않는" 흐름으로는 다른 세션과 같은 소스/같은 작업이 겹칠 수 있다.** 조회 시 대상 항목이 이미 `In Progress`(특히 최근 갱신)이면 같은 작업을 새로 시작하지 말고 사용자에게 확인한다.
3. **작업 수행 (Task Execution)**: 파악된 작업을 순차적으로 수행하며 필요한 코드를 수정하거나 작성합니다.
4. **커밋 전 서브에이전트 검수 (Pre-commit Subagent Review)**: 코드를 커밋하기 전에 해당 레포지토리의 서브에이전트(또는 특화된 페르소나 규칙)를 활용하여 코드를 검수합니다.
5. **검수 후 주석 및 커밋 메시지 표준화 작성 (Standardized Comments & Commit Message)**: 검수가 완료된 코드에 대해 표준화된 주석을 달고, 일관된 양식의 커밋 메시지를 작성합니다.
6. **배포 (Deployment)**: 작성된 코드를 알맞은 파이프라인이나 환경으로 배포합니다.
7. **배포 후 정상 동작 확인 (Post-deployment Verification)**: 배포가 완료된 후 시스템이 정상적으로 동작하는지 반드시 테스트하고 검증합니다.

**지속적인 업데이트 (Continuous Updates)**: 위 과정을 진행하면서 진행 상황은 아래 §4-1 인계 프로토콜(`progress.sh`)로 이슈에 남깁니다. (예전 이 문단은 "내부 `task.md` 를 동기화하라"고 지시했으나, 그런 파일은 이 머신에 존재한 적이 없다 — 선언만 있고 실체가 없는 규칙이었으므로 제거했다.) 특히, **작업이 완전히 끝났을 때는 커밋 메시지(`Closes #이슈번호`)를 활용하거나 `gh issue close` 명령어를 통해 반드시 깃허브 이슈를 '완료(Closed)' 처리해야 합니다.**

**세션 격리 (Worktree, Check & Claim의 보완책)**: Check & Claim은 "같은 작업"의 중복 착수를 막는 조치이고, 이것과 별개로 여러 세션(도구 무관)이 **같은 저장소**(`~/git/<repo>`)의 공용 클론을 동시에 건드리면 서로 다른 작업이어도 파일/브랜치가 물리적으로 충돌할 수 있다. 저장소 작업을 시작할 때는 공용 클론을 직접 건드리기보다 별도 worktree를 기본으로 삼는다.
- Claude Code는 `EnterWorktree` 도구로 `.claude/worktrees/<repo>/<name>` 아래 자동 생성/전환한다 — 기본 경로를 그대로 쓴다.
- Codex/Antigravity 등 자체 worktree 기능이 없는 도구는 `git worktree add ../<repo>-<slug> -b <branch>`로 수동 생성하고, 작업 종료 후 `git worktree remove`로 정리한다.
- **각 저장소 `.gitignore`에 `.claude/worktrees/`가 반드시 있어야 한다.** 없으면 `git add -A`/`git add .` 한 번에 worktree 디렉터리 전체가 gitlink(모드 160000)로 커밋되어 origin까지 올라갈 수 있다 — 2026-08-21 `customer.front`에서 실제로 발생·이미 push된 상태로 확인됨(별도 정리 필요, 이 문서 편집만으로는 해결되지 않음).

## 4-1. 인계 프로토콜 — 다른 도구가 중간부터 이어받게 하기

세 도구(Claude Code / Codex / Antigravity)가 **전부 같은 GitHub 계정으로 커밋**하므로 assignee·커밋 author 로는 누가 무엇을 잡고 있는지 구분되지 않는다. 진행 상태를 공유할 수 있는 매체는 **이슈 코멘트 하나뿐**이다. 도구별 메모리(예: Claude의 `~/.claude/projects/.../memory`)나 로컬 파일에 적으면 다른 도구는 영원히 못 읽는다.

### 세션 시작 (도구 무관, 필수)

```bash
~/msa/scripts/session-start.sh      # 활성/스테일 클레임 + 저장소별 브랜치·미커밋·미푸시 상태
```

Claude Code 는 SessionStart 훅이 자동 실행한다(로컬 모드). **훅이 없는 도구는 세션의 첫 명령으로 직접 실행할 것.**

### 코멘트 규격 (기계 판독용 첫 줄 + 사람이 읽는 본문)

| 종류 | 언제 | 명령 |
|------|------|------|
| `CLAIM` | 코드를 건드리기 **전** | `~/msa/scripts/claim.sh <repo> <issue>` |
| `PROGRESS` | 의미 있는 단위마다 | `~/msa/scripts/progress.sh <repo> <issue> "한 일\|다음 단계\|검증 방법"` |
| `HANDOFF` | 중단하거나 끝낼 때 | `~/msa/scripts/handoff.sh <repo> <issue> "남은 일/위험" [--done]` |
| `TAKEOVER` | 남의 스테일 클레임을 인수할 때 | `~/msa/scripts/claim.sh <repo> <issue> --takeover` |

- 코멘트 첫 줄은 ```CLAIM tool=... branch=... started=...``` 형태로 고정된다. 손으로 쓰지 말고 스크립트를 쓸 것 — 포맷이 깨지면 다른 세션의 클레임 판정이 틀린다.
- **실행 도구 식별**: 스크립트가 환경변수로 자동 판별한다. Codex/Antigravity 처럼 판별이 안 되는 도구는 셸에 `export AGENT_TOOL=codex`(또는 `antigravity`)를 설정하거나 `--tool` 로 지정한다.

### 스테일 클레임 만료 (2시간)

마지막 프로토콜 코멘트가 **2시간**(`MSA_CLAIM_STALE_SECONDS`) 넘게 없으면 그 클레임은 만료된 것으로 보고 `--takeover` 로 인수할 수 있다. 반납되지 않은 `In Progress` 가 영원히 남아 다른 세션을 막는 문제를 이 규칙으로 푼다(2026-08-21 실측: In Progress 11건 중 클레임 기록이 있는 것 0건, 일부는 며칠째 정지).

### 인계 가능 = 원격에 push된 상태

로컬 worktree 의 브랜치는 다른 도구·다른 세션 눈에 **보이지 않는다.** 작업을 중단할 때는 `wip:` 커밋이라도 push 한 뒤 `handoff.sh` 를 실행한다(미푸시 상태로 인계하려 하면 스크립트가 막는다). `--done` 없이 실행하면 Status 는 `In Progress` 로 남고 클레임만 반납되어, 다른 도구가 `--takeover` 로 바로 이어받는다.

### 어디에 무엇을 쓰나

| 내용 | 위치 |
|------|------|
| 진행 중 상태·다음 단계·인계 정보 | **이슈 코멘트**(위 프로토콜) |
| 확정된 개발 규칙 | `~/msa/AGENTS.md` (이 문서) |
| 사고 기록·ADR 등 장기 지식 | GitHub Wiki(gateway/order.api) |
| 도구 자신의 작업 효율용 메모 | 각 도구의 메모리 — **다른 도구는 못 읽는다는 전제로만 사용** |
<!-- canon:end -->
