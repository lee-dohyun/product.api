# product.api

상품 정보를 제공하는 Spring Boot 3.5 / Java 21 서비스. `customer` 네임스페이스의 PostgreSQL(`catalogdb`)에 상품을 저장하고, 단일 상품 상세 조회(`GET /api/products/{id}`)만 Redis(`product:{id}`, TTL 10분)로 캐싱한다.

## 테이블

- `categories(id, name)`
- `products(id, category_id, name, description, price, stock_quantity, created_at, updated_at)`
- `product_images(id, product_id, image_url, sort_order)`

스키마는 `spring.jpa.hibernate.ddl-auto: update`로 애플리케이션 기동 시 자동 생성된다.

## API

- `GET /api/products` — 목록 (캐싱 안 함)
- `GET /api/products/{id}` — 단일 상품 상세 (Redis 캐싱)
- `POST /api/products` / `PUT /api/products/{id}` / `DELETE /api/products/{id}` — 수정 시 캐시 evict
- `GET /api/categories` / `POST /api/categories`

## 환경 변수

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `SERVER_PORT` (기본값은 `application.yml` 참고, customer 네임스페이스의 `catalog-postgres`/`redis-service` 기준).

## 실행

```bash
./gradlew build
./gradlew bootRun
```
