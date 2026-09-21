# Camunda 8.9 Self-Managed Playground — Compat test với client 8.10

Spike thử nghiệm: dựng **Camunda 8.9 full stack** bằng docker compose, rồi chạy **Spring Boot app dùng Camunda Java Client 8.10** gọi sang, tập trung tái hiện và xử lý lỗi **401 Unauthorized** ở Keycloak token endpoint và các REST API.

> **Kết luận nhanh:** client 8.10 (`camunda-client-java 8.10.0-alpha5-rc3`) **tương thích** với server 8.9.21 ở mức wire protocol. Mọi lỗi gặp phải (401/403) đều nằm ở **cấu hình Identity/Keycloak/authorization**, không phải bất tương thích thư viện.

---

## 1. Cấu trúc thư mục

```
camunda/
├── compose-8.9/                        # clone của camunda/camunda-distributions tag docker-compose-8.9
│   └── docker-compose/versions/camunda-8.9/
│       ├── docker-compose-full.yaml    # compose đầy đủ (dùng file này)
│       ├── docker-compose.yaml         # bản lightweight (không Optimize/Identity/Keycloak)
│       ├── .env                        # versions + secrets mặc định
│       ├── .orchestration/application.yaml   # config Camunda Run (orchestration)
│       └── .identity/application.yaml        # config Identity (provision users/clients/roles)
├── app810/                             # Spring Boot app test (throwaway)
│   ├── pom.xml                         # camunda-client-java 8.10.0-alpha5-rc3
│   ├── .env                            # credentials cho app
│   └── src/main/
│       ├── java/com/example/camunda/CompatRunner.java   # chạy qua các API chính
│       └── resources/
│           ├── application.properties
│           └── process.bpmn            # 2 process: none-start + message-start
└── README.md
```

## 2. Stack Camunda 8.9 — thành phần & port

File dùng: `docker-compose-full.yaml`. Các phiên bản (từ `.env`):

| Component | Image | Version | Port host | Ghi chú |
|---|---|---|---|---|
| Orchestration (Zeebe + Operate + Tasklist) | `camunda/camunda` | 8.9.21 | **8080** (REST/UI), **26500** (gRPC), 9600 (management) | consolidated auth |
| Connectors | `camunda/connectors-bundle` | 8.9.12 | 8086 | |
| Optimize | `camunda/optimize` | 8.9.21 | 8083 | |
| Identity | `camunda/identity` | 8.9.9 | **8084** | Management Identity UI |
| Keycloak | `camunda/keycloak` | quay-26.6.4 | **18080** | realm `camunda-platform`, context path `/auth` |
| Elasticsearch | `elasticsearch` | 8.19.11 | 9200, 9300 | |
| Web Modeler REST API | `camunda/web-modeler-restapi` | 8.9.9 | 8070 | |
| Web Modeler WebSockets | `camunda/web-modeler-websockets` | 8.9.9 | 8060 | |
| Console | `camunda/console` | 8.9.109 | 8087 | |
| PostgreSQL (Keycloak/Identity) | `postgres` | 15-alpine3.22 | internal | |
| Mailpit | `axllent/mailpit` | v1.21.8 | 8075 (UI), 1025 (SMTP) | |

### Lệnh điều hành

```bash
cd compose-8.9/docker-compose/versions/camunda-8.9

# Start (lần đầu pull ~vài GB, chờ hết healthcheck)
docker compose -f docker-compose-full.yaml up -d

# Kiểm tra trạng thái
docker ps --format '{{.Names}}\t{{.Status}}'

# Stop (giữ dữ liệu)
docker compose -f docker-compose-full.yaml down

# Stop + xóa volumes (dọn sạch)
docker compose -f docker-compose-full.yaml down -v

# Restart một component (sau khi sửa file config mount)
docker compose -f docker-compose-full.yaml restart orchestration
```

**Thứ tự phụ thuộc** do compose quản: ES → orchestration, postgres → keycloak → identity → connectors/optimize/console/web-modeler. Đợi `healthy` hết mới test.

### URL sau khi chạy

| UI | URL |
|---|---|
| Operate | http://localhost:8080/operate |
| Tasklist | http://localhost:8080/tasklist |
| Identity | http://localhost:8084 |
| Keycloak Admin | http://localhost:18080/auth (realm `master`, login `admin`/`admin`) |
| Optimize | http://localhost:8083 |
| Web Modeler | http://localhost:8070 |
| Console | http://localhost:8087 |

## 3. Tài khoản & credentials

| Mục đích | Username / Client ID | Password / Secret |
|---|---|---|
| Identity UI login (user) | `demo` | `demo` |
| Keycloak master realm (admin) | `admin` | `admin` |
| Client cho app (Zeebe REST/gRPC, Operate, Tasklist) — tự tạo | `spring-boot-app` | `Q1cLMKoFi9HGPaE8WuebEYFRyN4egfa8` |
| Client built-in Orchestration | `orchestration` | `secret` |
| Client built-in Connectors | `connectors` | `demo-connectors-secret` |
| Client built-in Optimize | `optimize` | `demo-optimize-secret` |
| Client built-in Console | `console` | `demo-console-secret` |
| Client built-in Identity | `camunda-identity` | ⚠️ xem mục 7.4 |

> Realm dùng cho app: **`camunda-platform`**. Token endpoint:
> `POST http://localhost:18080/auth/realms/camunda-platform/protocol/openid-connect/token`
> với `grant_type=client_credentials&client_id=...&client_secret=...`

> ⚠️ **Không có client `zeebe`** trong realm này (dễ sai nếu copy config từ SaaS/Zeebe cũ) — dùng `orchestration` hoặc client tự tạo.

## 4. Cấu hình Identity để app gọi được API (3 bước)

### Bước 1 — Tạo application cho app (chữa 401 `invalid_client`)

1. Mở **Identity UI** `http://localhost:8084`, login `demo`/`demo` (không phải `admin/admin` — đó là Keycloak master).
2. **Applications** → **Add application**
   - Name: `spring-boot-app`
   - Type: **M2M** (app chỉ gọi API bằng `client_credentials`, không user login)
3. Mở application → tab **Application details** → **Show secret** → copy Client ID + secret.

Identity tự sync client + service account sang Keycloak realm `camunda-platform`. Từ đây token grant trả 200:

```bash
curl -s -X POST http://localhost:18080/auth/realms/camunda-platform/protocol/openid-connect/token \
  -d 'grant_type=client_credentials&client_id=spring-boot-app&client_secret=<SECRET>'
```

### Bước 2 — Assign permissions API (chữa thiếu audience/claims)

1. Trong application → tab **Access to APIs** → **Assign permissions**
2. **Select an API**: `Orchestration API` (audience `orchestration-api` — bảo vệ Zeebe REST/gRPC, Operate, Tasklist)
3. Tick **`read:*`** + **`write:*`** → **Add**

→ Token giờ có `aud: [..., orchestration-api]` + `permissions: {orchestration-api: [read:*, write:*]}`.

**Chọn API nào?** Theo endpoint app gọi:

| App gọi | Chọn API trong dropdown |
|---|---|
| Zeebe REST `/v2/...`, gRPC, Operate, Tasklist | **Orchestration API** |
| Web Modeler public REST | Web Modeler API |
| Optimize | Optimize API |
| Console | Console API |

### Bước 3 — Đưa client vào role của Orchestration cluster (chữa 403)

⚠️ Bước này **không có trên UI** (Identity 8.9 không quản lý authorization của orchestration cluster). Sửa file config Orchestration:

```yaml
# compose-8.9/docker-compose/versions/camunda-8.9/.orchestration/application.yaml
camunda:
  security:
    initialization:
      defaultRoles:
        admin:
          users:
            - demo
          clients:
            - orchestration
            - spring-boot-app   # <-- thêm client của app
```

Rồi restart:

```bash
docker compose -f docker-compose-full.yaml restart orchestration
```

Không có bước này: token đủ permissions nhưng vẫn **403** `FORBIDDEN: Insufficient permissions to perform operation 'CREATE' on resource 'RESOURCE'`.

## 5. App Spring Boot với client 8.10

### Phiên bản

- Spring Boot 3.5.7, Java 21
- `io.camunda:camunda-client-java:8.10.0-alpha5-rc3`
- ⚠️ **8.10 GA chỉ phát hành 13/10/2026** — hiện dùng alpha mới nhất. `spring-boot-starter-camunda-sdk` chưa có bản 8.10 nên app dùng client trực tiếp (không qua starter).

### Điểm đổi API của client 8.10 (so với 8.9)

| 8.9 | 8.10 |
|---|---|
| `io.camunda.zeebe.client.ZeebeClient` | `io.camunda.client.CamundaClient` |
| package `io.camunda.zeebe.client.*` | `io.camunda.client.*` |
| `.gatewayAddress(String)` | `.grpcAddress(URI)` |
| `.usePlaintext()` | **đã bỏ** (plaintext là mặc định khi không cấu hình TLS) |
| `getConfiguration().getVersion()` | không còn — hardcode/đọc từ pom |

### Chạy app

```bash
cd app810
set -a && source .env && set +a
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--camunda.client.auth.client-id=$CAMUNDA_CLIENT_AUTH_CLIENT_ID \
  --camunda.client.auth.client-secret=$CAMUNDA_CLIENT_AUTH_CLIENT_SECRET \
  --camunda.client.auth.token-url=$CAMUNDA_CLIENT_AUTH_TOKEN_URL"
```

`.env` của app:

```properties
CAMUNDA_CLIENT_ZEEBE_GATEWAY_URL=http://localhost:26500
CAMUNDA_CLIENT_ZEEBE_REST_URL=http://localhost:8080
CAMUNDA_CLIENT_AUTH_TOKEN_URL=http://localhost:18080/auth/realms/camunda-platform/protocol/openid-connect/token
CAMUNDA_CLIENT_AUTH_CLIENT_ID=spring-boot-app
CAMUNDA_CLIENT_AUTH_CLIENT_SECRET=Q1cLMKoFi9HGPaE8WuebEYFRyN4egfa8
```

### Flow test trong `CompatRunner` (kết quả thực tế)

| # | Bước | Protocol | Kết quả |
|---|---|---|---|
| 1 | Topology | gRPC | OK — báo server `8.9.21` |
| 2 | Deploy 2 processes (`process.bpmn`) | REST `/v2/deployments` | OK |
| 3 | Create instance | REST `/v2/process-instances` | OK |
| 4 | Publish message `order-received` | REST `/v2/messages` | OK — kích hoạt message start event |
| 5 | Search process instances | REST `/v2/process-instances/search` | OK (0 hits nếu ES index chưa kịp) |
| 6 | Search user tasks | REST `/v2/user-tasks/search` | OK |
| 7 | Job worker `compat-task` + complete | gRPC | OK — nhận đúng variables |

Kết quả chạy: `ALL STEPS COMPLETED`.

### Lưu ý BPMN

- `<bpmn:message>` phải nằm ở cấp `<definitions>`, không được trong `<process>` (lỗi 400 `cvc-complex-type.2.4.a` khi deploy).
- Process chỉ có message start event thì **không create instance trực tiếp được** (409 `INVALID_STATE`) — cần none start event, hoặc để message kích hoạt.

## 6. Ma trận lỗi 401/403 và cách xử lý

| Triệu chứng | Nguyên nhân | Xử lý |
|---|---|---|
| `POST .../openid-connect/token` → **401** `invalid_client` | `client_id` không tồn tại (vd `zeebe`), sai secret, hoặc client không bật service account | Bước 1 — tạo M2M app trong Identity, lấy đúng secret |
| API trả **401** (không token / token sai) | Thiếu `Authorization: Bearer <token>` hoặc token hết hạn (TTL 5 phút mặc định) | Lấy token mới trước mỗi session |
| **403** `FORBIDDEN: Insufficient permissions to perform operation '...' on resource '...'` | Client chưa nằm trong `defaultRoles` của orchestration, hoặc thiếu permissions `read:*`/`write:*` | Bước 2 + Bước 3 |
| API trả **200 nhưng list rỗng** khi có data | Client có token hợp lệ nhưng authorization filter theo tenant/resource lọc kết quả | Kiểm tra Bước 3 + dữ liệu ES đã index |

Kiểm tra nhanh 401 vs 200 bằng curl:

```bash
# Không token -> 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/v2/process-instances/search \
  -H 'Content-Type: application/json' -d '{}'

# Có token -> 200
TOKEN=$(curl -s -X POST http://localhost:18080/auth/realms/camunda-platform/protocol/openid-connect/token \
  -d 'grant_type=client_credentials&client_id=spring-boot-app&client_secret=<SECRET>' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/v2/process-instances/search \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
```

Xem claims trong token (audience + permissions):

```bash
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{aud, permissions}'
```

## 7. Gotchas đã gặp (đề phòng)

1. **Login Identity UI bằng `demo`/`demo`** — `admin/admin` chỉ dùng cho Keycloak master realm. Sai cặp này → "Please enter a valid username or password".
2. **Không cấu hình tay Keycloak** — Identity là owner của realm `camunda-platform`, có thể ghi đè phần sửa tay khi restart. Chỉ config qua Identity UI; Keycloak console chỉ để xem.
3. **Restart Identity** (`docker compose -f docker-compose-full.yaml restart identity`) giúp khôi phục UI khi dialog báo *"The list of APIs could not be loaded"* / *"Unknown error"* — lỗi này do Identity không tự lấy được token từ Keycloak.
4. **Secret của client `camunda-identity`** trong Keycloak có thể **không khớp** `VALUES_CAMUNDA_IDENTITY_CLIENT_SECRET` trong `.env` (Keycloak sinh secret riêng lúc provision). Khi cần dùng client này, lấy secret thật từ Keycloak admin API:
   ```bash
   KC_TOKEN=$(curl -s -X POST http://localhost:18080/auth/realms/master/protocol/openid-connect/token \
     -d 'grant_type=password&client_id=admin-cli&username=admin&password=admin' \
     | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
   curl -s -H "Authorization: Bearer $KC_TOKEN" \
     'http://localhost:18080/auth/admin/realms/camunda-platform/clients?clientId=camunda-identity' | jq -r '.[0].secret'
   ```
5. **Browser truy cập `localhost:8084`** đôi khi lỗi DNS trong sandbox → dùng `http://127.0.0.1:8084`.
6. **Sửa file config mount vào container phải restart** service tương ứng mới có hiệu lực.

## 8. Kết luận compat 8.10 client → 8.9 server

- **Wire protocol tương thích ngược**: gRPC topology + REST v2 deploy/create/publish/search/worker đều hoạt động giữa client `8.10.0-alpha5-rc3` và server `8.9.21`.
- **Breaking changes nằm ở API surface của thư viện** (đổi package, đổi tên class/builder method) — phải sửa code khi nâng lên 8.10, xem bảng ở mục 5.
- Chưa test trên bản 8.10 GA (phát hành 13/10/2026) — khi GA nên chạy lại spike này với dependency `8.10.0` chính thức.

## 9. Dọn dẹp

```bash
# Dừng + xóa volumes (mất toàn bộ dữ liệu stack)
cd compose-8.9/docker-compose/versions/camunda-8.9
docker compose -f docker-compose-full.yaml down -v
```

App `app810/` là throwaway — có thể xóa hoặc giữ làm mẫu cấu hình client 8.10.
