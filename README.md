# Ticket2h core-service

[![CI](https://github.com/ticket2h/core-service/actions/workflows/ci.yml/badge.svg)](https://github.com/ticket2h/core-service/actions/workflows/ci.yml)

Lõi bán vé của Ticket2h: sự kiện, sơ đồ ghế, giữ chỗ, đơn hàng, thanh toán qua MockPay, phát vé QR, check-in,
chuyển nhượng. Spring Boot 4.1, Java 25, PostgreSQL 17, Kafka 4, Redis 7, Flyway. Cổng 8080.

Đây là phần lõi của dự án TicketRush M1–M3 (`ticketrush-m2-m3-huong-dan.md`) sau khi
tách microservice, cộng thêm M4 (dữ liệu lớn), M5 (sơ đồ ghế trực tiếp, phòng chờ, giới hạn request, cache, chạy
nhiều instance) và một phần M6 (đối soát thanh toán, lịch sử thao tác). Email vé đã chuyển sang `notification-service`; tài khoản, đăng nhập và phát JWT sang
`identity-service` (core chỉ kiểm tra JWT bằng JWKS). Kiến trúc cả hệ thống ở [infra/docs/kien-truc.md](https://github.com/ticket2h/infra/blob/main/docs/kien-truc.md).

Mới học Spring? Đọc [`docs/huong-dan-hoc-code.md`](docs/huong-dan-hoc-code.md): giải thích code và chức năng từ cơ bản.

## Chạy

Cần PostgreSQL, Kafka, Redis, các biến ở [`.env.example`](.env.example), và identity-service để kiểm tra đăng nhập.
Redis không chạy thì core vẫn bán vé được (sơ đồ ghế quay về tải lại định kỳ, giới hạn request tạm bỏ qua), chỉ sự
kiện bật phòng chờ là không vào hàng được. Cách nhanh
nhất: clone repo [infra](https://github.com/ticket2h/infra) cạnh repo này (hướng dẫn từ máy trống:
[infra/docs/chay-tren-may-moi.md](https://github.com/ticket2h/infra/blob/main/docs/chay-tren-may-moi.md)):

```bash
(cd ../infra && scripts/init-env.sh && docker compose --profile apps up -d)   # hạ tầng + các service khác từ image
(cd ../infra && docker compose stop core-service)                             # nhường cổng 8080 cho bản đang sửa
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev      # :8080, profile dev tạo dữ liệu mẫu
```

Lúc core chạy ngoài Docker, MockPay và notification-service trong container không gọi tới được nó (chúng tìm
`core-service` theo tên trong compose): thanh toán không có webhook, không có email. Thử trọn luồng mua vé thì chạy cả
bốn service bằng `./mvnw` (chỉ `docker compose up -d` cho hạ tầng), hoặc chạy cả bốn từ image.

Service đọc secret từ `../infra/.env` nếu repo [infra](https://github.com/ticket2h/infra) nằm cạnh repo này, từ `.env` của chính repo
(mẫu: [`.env.example`](.env.example), chỉ gồm biến service này cần), hoặc từ biến môi trường của máy.

Image Docker: `ghcr.io/ticket2h/core-service` (CI build mỗi lần push lên `main`). Build tại máy: `docker build -t core-service .`
(không cần JDK). Chạy cả hệ thống từ image: `docker compose --profile apps up -d` trong repo infra.

Mọi API cần đăng nhập cần `identity-service` (8082) đang chạy: khách đăng nhập ở đó, và core lấy public key từ đó
để kiểm tra token. Muốn chạy trọn hành trình mua vé thì bật thêm `notification-service` (8081) và
`mock-payment-gateway` (9090), rồi chạy `../infra/scripts/demo.sh`.

### Dữ liệu mẫu (profile dev)

Tài khoản mẫu (`admin`, `organizer1`, `staff1`, `buyer1..3` `@test.local`, mật khẩu `Passw0rd!`) do identity-service
tạo với id cố định ([infra/docs/contracts.md](https://github.com/ticket2h/infra/blob/main/docs/contracts.md), mục 6). core tạo sự kiện mẫu "Sơn Tùng M-TP Live Concert 2026" cho
ban tổ chức Sky và phân công `staff1` soát vé: 60 ghế VIP (1.500.000đ) và 1.000 vé GA (800.000đ). Log khi khởi
động in ra `EVENT_ID`, `GA_TYPE_ID`, `VIP_TYPE_ID`.

### Dữ liệu lớn (M4, profile datagen)

Công cụ sinh dữ liệu (`dev/datagen`, ADR-021 ở [infra/docs/adr](https://github.com/ticket2h/infra/tree/main/docs/adr))
giả lập hệ thống đã chạy 2 năm: 2.000 sự kiện, 10 triệu đơn, thanh toán, vé, 30 triệu lượt quét, và một sự kiện đang
mở bán 200.000 đơn ("Đại nhạc hội Ticket2h 2026", của ban tổ chức Sky: đăng nhập `organizer1@test.local` để xem báo
cáo). Người mua là 2 triệu tài khoản do công cụ sinh của identity-service tạo, cùng công thức id (`DatagenIds`).
Cả hệ thống một lệnh: `../infra/scripts/datagen.sh` (hoặc `--small`). Chạy riêng core bằng `./mvnw`:

```bash
# identity-service phải sinh tài khoản trước, cùng các con số customers/organizers/staff
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,datagen \
  -Dspring-boot.run.arguments="--ticket2h.datagen.orders=1000000 --ticket2h.datagen.checkins=3000000"
```

Ghi bằng `COPY` (không qua API, không qua nghiệp vụ), mỗi sự kiện một transaction, 4 luồng song song. Bảng tính lại
được từ bảng gốc (tổng hợp doanh thu, hạn mức mua) được tính bằng SQL sau cùng, nên dữ liệu thoả mọi bất biến
(`CoreDataGeneratorIT` kiểm tra). Chạy xong thì app tự tắt; database đã có dữ liệu sinh thì không sinh chồng.

## Test

```bash
./mvnw verify                                   # 55 unit test + 168 integration test + 2 test trong heap 256MB
./mvnw test -Dtest=RequestHashTest              # một class unit test
./mvnw verify -Dit.test=SeatClaimConcurrencyIT -DskipExportMemoryIT=true   # một integration test qua failsafe
```

`ExportMemoryIT` (xuất 500.000 dòng) chạy trong một JVM riêng `-Xmx256m` (execution `export-memory` trong `pom.xml`),
nên giới hạn heap áp dụng thật cho cả app. Khi chạy lẻ một test thì thêm `-DskipExportMemoryIT=true` để bỏ qua nó.

Integration test chạy Postgres và Kafka thật bằng Testcontainers, nên cần Docker. Lần đầu tải image mất vài phút.
Lớp cha `IntegrationTest` dọn dữ liệu bằng `TRUNCATE` trước mỗi test (không dùng `@Transactional`, vì sẽ làm test
đồng thời vô nghĩa). Đồng hồ là `MutableClock`, cổng thanh toán là `FakeGateway` (HTTP server của JDK),
identity-service là `FakeIdentity` (JWKS với khoá RSA sinh lúc chạy test, tra tài khoản theo email và theo lô id). Tài khoản test nằm trong
`FakeIdentity`, không nằm trong database; `TestData.token` ký JWT RS256 như identity thật. Dữ liệu lớn (hàng trăm
nghìn đơn, vé, lượt quét) sinh ngay trong PostgreSQL bằng `generate_series` qua `BulkData`.

## Tiêu chí của từng mốc

| Tiêu chí | Test |
|---|---|
| M1: BTC A sửa sự kiện của BTC B → 403 | `OrganizerCatalogApiIT.organizer_cannot_edit_another_organizers_event` |
| M1: gõ "son tung" ra "Sơn Tùng" | `OrganizerCatalogApiIT.searching_without_diacritics_finds_vietnamese_titles` |
| M2: 200 request cùng lúc giành 1 ghế → đúng 1 thành công | `SeatClaimConcurrencyIT` |
| M2: 10.000 request giành 1.000 vé GA → bán đúng 1.000 | `GaInventoryConcurrencyIT`, `OrderPlacementIT.ten_thousand_orders_for_one_thousand_ga_tickets` |
| M2: gửi song song nhiều tab không vượt 4 vé | `PurchaseQuotaConcurrencyIT` |
| M2: webhook gửi 3 lần → chỉ 1 thanh toán | `PaymentWebhookIT` (lần lượt và song song) |
| M3: 1 QR quét cùng lúc ở 2 cổng → 1 thành công | `CheckinIT.same_qr_scanned_at_two_gates_at_once_succeeds_exactly_once` |
| M3: sửa 1 ký tự QR (kể cả ký tự cuối) → bị từ chối | `QrTokenCodecTest` |
| M3: vé đã chuyển nhượng → QR cũ bị từ chối | `TicketTransferIT` |
| M3: tắt app giữa chừng, khởi động lại → vẫn có vé | `CrashRecoveryIT` |
| M3: message trùng không tạo thêm vé | `TicketIssuanceIT` |
| M3: message trùng không gửi thêm email | `TicketsIssuedEmailIT` (repo notification-service) |
| M4: báo cáo một sự kiện 200.000 đơn dưới 1 giây | `SalesReportPerformanceIT` (đo 4–5 ms; tính thẳng từ bảng đơn 164 ms) |
| M4: xuất 500.000 dòng với heap 256MB | `ExportMemoryIT` (danh sách khách và lịch sử soát vé, JVM `-Xmx256m`) |
| M4: import 50.000 ghế, báo đúng các dòng lỗi | `SeatImportIT` (lỗi ở dòng 2, 25.001 và dòng cuối 50.001) |
| M4: trang thứ 10.000 của danh sách vẫn nhanh | `OrderPagingIT` (keyset đọc 21 dòng index; OFFSET đọc hơn 200.000 dòng) |
| M4: số liệu báo cáo đúng, theo ngày giờ Việt Nam, không cộng trùng; cột sắp xếp chỉ nhận giá trị cho phép | `SalesReportApiIT` |
| Microservice: hợp đồng với notification-service | `TicketEventsContractIT`, `InternalApiIT` |
| Microservice: chỉ tin JWT do identity ký (RS256), từ chối khoá lạ, HS256, sai issuer | `JwtVerificationIT` |
| Microservice: identity sập → 502, không đổi dữ liệu; không dò được email | `IdentityOutageIT`, `JwksUnavailableIT`, `OrganizerStaffApiIT` |
| M5: ghế đổi trạng thái hiện ở máy khác dưới 1 giây, kể cả khi nối vào instance khác | `SeatMapLiveIT` (chạy thêm một instance core thật trong test) |
| M5: 1.000 thay đổi mỗi giây gửi theo lô 300 ms; mất mạng nối lại nhận đủ trạng thái | `SeatMapLiveIT` (30 ghế đổi gần như cùng lúc → tối đa vài lô; `resync` khi lỡ message) |
| M5: phòng chờ, mỗi giây N người, ai cũng thấy thứ tự của mình | `WaitingRoomIT` |
| M5: giới hạn request theo user và IP | `RateLimitIT` |
| M5: cache không bị dồn truy vấn khi nhiều người mở cùng lúc | `CacheStampedeIT` (200 request, 1 truy vấn), `RefreshAheadCacheTest` |
| M5: giữ ghế bằng database so với có Redis lọc trước | `SeatGateComparisonIT` (số đo ở ADR-028) |
| M5: load test giờ mở bán: không bán trùng, không 5xx, p95 | `../infra/scripts/loadtest.sh` (k6, ADR-029 ở infra) |
| M6: đơn kẹt ở chờ thanh toán được job đối soát sửa | `PaymentReconciliationIT` |
| M6: lịch sử thao tác nhạy cảm, không sửa xoá được | `AuditLogIT` |

Các test quan trọng nằm trong package `acceptance`: không sửa để cho xanh, sửa code.

### Thử tay

- **Chaos MockPay** (Bước 12.2): `curl -X PUT localhost:9090/admin/chaos -H 'Content-Type: application/json' -d '{"duplicateDeliveries":3}'`
  rồi chạy `../infra/scripts/demo.sh` → đơn PAID một lần, `payment_webhook_logs` có 3 dòng. Các chế độ khác:
  `webhookDelayMs`, `dropWebhook`, `webhookBeforeRedirect`, `failFirstDeliveries`, `refundFailureRate`.
  `DELETE /admin/chaos` để về mặc định. Chaos áp dụng cho phiên thanh toán tạo **sau** lệnh PUT.
- **App sập** (Bước 20.2): chạy với `--ticket2h.outbox.relay.enabled=false`, mua và thanh toán, `kill -9`, chạy lại
  bình thường → vài giây sau có vé (và email, nếu notification-service đang chạy).
- **Kafka sập**: trong `../infra`: `docker compose stop kafka`, thanh toán vài đơn (đơn đứng ở PAID),
  `docker compose start kafka` → mọi đơn có vé.
- **notification-service sập**: tắt nó, mua vé → đơn vẫn FULFILLED; bật lại → email tới bù.
- **identity-service sập**: token đã đăng nhập vẫn dùng được (core giữ public key trong bộ nhớ); chuyển nhượng vé,
  phân công soát vé, xem danh sách soát vé, danh sách đơn, tải danh sách khách trả 502 `IDENTITY_UNAVAILABLE` và không
  đổi dữ liệu.
- **Kế hoạch truy vấn trên dữ liệu lớn** (sau `../infra/scripts/datagen.sh`): `docker exec -it ticket2h-postgres-1 psql -U core`,
  rồi `EXPLAIN (ANALYZE, BUFFERS)` các câu trong ADR-016…019 để thấy chỗ khác nhau giữa OFFSET và keyset, giữa tính
  thẳng và bảng tổng hợp, và partition pruning.
- **Bất biến dữ liệu**: `docker exec -i ticket2h-postgres-1 psql -U core < scripts/invariants.sql`.
- **Sơ đồ ghế trực tiếp qua hai instance** (M5): trong `../infra`: `docker compose --profile apps --profile scale up -d`,
  mở trang sự kiện ở hai trình duyệt (gateway chia hai trình duyệt vào hai instance), giữ ghế ở một bên → bên kia thấy
  ghế xám ngay. Xem luồng thô: `curl -N localhost:8080/events/<eventId>/seats/stream`.
- **Redis sập** (M5): `docker compose stop redis` → vẫn đặt vé được, sơ đồ ghế tải lại 15 giây một lần, phòng chờ
  trả 503; `docker compose start redis` → trình duyệt tự nối lại SSE.
- **Gọi API nội bộ bằng tay**: `curl -H "X-Internal-Api-Key: $INTERNAL_API_KEY_NOTIFICATION" 'localhost:8080/internal/tickets?ids=<ticketId>'`
  (khoá lấy trong `../infra/.env`; tra tài khoản giờ ở identity-service: `localhost:8082/internal/users/<userId>`).

## Cấu trúc

```
core-service/
├── src/main/java/com/ticket2h/
│   ├── common/        cấu hình, Clock, lỗi (ProblemDetail), kiểm tra JWT (JWKS) và khoá API nội bộ, event Kafka,
│   │                  đọc/ghi CSV (csv/), cursor phân trang keyset (paging/), bảng con theo tháng (persistence/),
│   │                  cache làm mới ở nền (cache/), giới hạn request (ratelimit/), cầu dao Redis (redis/)
│   ├── audit/         lịch sử thao tác nhạy cảm (M6) và API cho admin
│   ├── identity/      client gọi identity-service (tra tài khoản theo email, theo lô id); không còn lưu tài khoản
│   ├── catalog/       địa điểm, sự kiện, loại vé, sơ đồ ghế (cả nhập từ file CSV), tìm kiếm không dấu
│   ├── inventory/     giữ ghế, tồn kho GA, hạn mức mỗi user, sơ đồ ghế công khai và trực tiếp (SSE, Redis
│   │                  pub/sub), lớp lọc ghế bằng Redis (bản thử)
│   ├── waitingroom/   phòng chờ lúc mở bán (hàng chờ trong Redis, vé vào cửa ký HMAC)
│   ├── ordering/      đơn hàng, idempotency, hết hạn, huỷ, kênh bán; danh sách đơn cho ban tổ chức (keyset)
│   ├── payment/       tạo thanh toán, webhook, hoàn tiền, đối soát với cổng
│   ├── outbox/        ghi và phát outbox event, cấu hình Kafka
│   ├── ticketing/     phát vé, QR, check-in, chuyển nhượng; API nội bộ /internal/tickets; file CSV danh sách
│   │                  khách, lịch sử soát vé; bảng con theo tháng của checkin_logs
│   ├── reporting/     báo cáo doanh thu: hàng đợi, job cộng dồn vào bảng tổng hợp, API cho ban tổ chức
│   └── dev/           dữ liệu mẫu và endpoint chỉ có ở profile dev; công cụ sinh dữ liệu lớn (datagen/)
├── src/main/resources/db/migration/   Flyway V1–V2 (M1), V100–V102 (M2), V110–V112 (M3), V113–V114 (tách notification, identity),
│                                      V115–V117 (M4), V118–V119 (M6), V120–V121 (M5)
├── docs/                              api.md, schema.md, adr/ (001–010, 016–020, 022, 024, 025, 027, 028), huong-dan-hoc-code.md
├── scripts/invariants.sql
├── Dockerfile, .env.example           image và biến môi trường (CI: .github/workflows/ci.yml)
└── CLAUDE.md                          quy ước cho agent
```

## Job chạy nền (khi `ticket2h.jobs.enabled=true`, mặc định bật; test tắt)

| Job | Chu kỳ | Việc |
|---|---|---|
| `OrderExpiryJob` | 5 giây | Dọn đơn quá hạn giữ chỗ + 1 phút, trả ghế, vé GA, hạn mức |
| `RefundJob` | 10 giây | Hoàn tiền payment `REFUND_PENDING`, thử lại theo lịch lùi dần |
| `PaidOrderSweeper` | 1 phút | Lưới an toàn: đơn PAID quá 5 phút chưa có vé thì phát vé trực tiếp |
| `OutboxCleanup` | 1 giờ | Xoá outbox đã gửi quá 7 ngày |
| `OutboxRelay` | 0,5 giây | Đẩy outbox lên Kafka (bật/tắt riêng bằng `ticket2h.outbox.relay.enabled`) |
| `SalesRollup.applyPending` | 5 giây | Cộng đơn vừa trả tiền vào bảng tổng hợp doanh thu, mỗi lô 500 đơn (ADR-016) |
| `CheckinLogPartitions` | 1 ngày | Tạo trước bảng con theo tháng của `checkin_logs` cho 3 tháng tới (ADR-019) |
| `PaymentReconciliation` | 1 phút | Hỏi MockPay về payment chưa có kết quả sau 2 phút, xử lý như webhook (ADR-022) |
| `AuditLogPartitions` | 1 ngày | Tạo trước bảng con theo tháng của `audit_logs` cho 3 tháng tới |
| `WaitingRoom.admitDue` | 1 giây | Cho N người đầu hàng vào ở mọi sự kiện có phòng chờ đang bán; Redis bảo đảm mỗi giây chỉ một instance làm (ADR-027) |

## Việc để các mốc sau

- api-gateway: [infra/docs/kien-truc.md](https://github.com/ticket2h/infra/blob/main/docs/kien-truc.md), mục "Lộ trình tách tiếp".
- Email vé vào DLT của notification-service (mail server sập quá 10 phút) phải gửi lại tay.
- Bộ đếm GA một dòng là điểm nghẽn đã biết (ADR-003): load test M5 mới đo ghế có số, chưa đo riêng vé GA.
- Sơ đồ ghế theo khu (sự kiện 100.000 ghế: ảnh chụp 20MB, nén còn 2MB): tải và nghe SSE theo từng khu (ADR-024).
- M6 còn lại: metrics và dashboard, dead letter queue, mã hoá số điện thoại, deploy.
