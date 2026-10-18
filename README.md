# Ticket2h core-service

[![CI](https://github.com/ticket2h/core-service/actions/workflows/ci.yml/badge.svg)](https://github.com/ticket2h/core-service/actions/workflows/ci.yml)

Lõi bán vé của Ticket2h: sự kiện, sơ đồ ghế, giữ chỗ, đơn hàng, thanh toán qua MockPay, phát vé QR, check-in,
chuyển nhượng. Spring Boot 4.1, Java 25, PostgreSQL 17, Kafka 4, Flyway. Cổng 8080.

Đây là phần lõi của dự án TicketRush M1–M3 (`ticketrush-m2-m3-huong-dan.md`) sau khi
tách microservice. Email vé đã chuyển sang `notification-service`; tài khoản, đăng nhập và phát JWT sang
`identity-service` (core chỉ kiểm tra JWT bằng JWKS). Kiến trúc cả hệ thống ở [infra/docs/kien-truc.md](https://github.com/ticket2h/infra/blob/main/docs/kien-truc.md).

Mới học Spring? Đọc [`docs/huong-dan-hoc-code.md`](docs/huong-dan-hoc-code.md): giải thích code và chức năng từ cơ bản.

## Chạy

Cần PostgreSQL, Kafka, các biến ở [`.env.example`](.env.example), và identity-service để kiểm tra đăng nhập. Cách nhanh
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

## Test

```bash
./mvnw verify                                   # 48 unit test + 122 integration test (*IT)
./mvnw test -Dtest=RequestHashTest              # một class unit test
./mvnw verify -Dit.test=SeatClaimConcurrencyIT  # một integration test qua failsafe
```

Integration test chạy Postgres và Kafka thật bằng Testcontainers, nên cần Docker. Lần đầu tải image mất vài phút.
Lớp cha `IntegrationTest` dọn dữ liệu bằng `TRUNCATE` trước mỗi test (không dùng `@Transactional`, vì sẽ làm test
đồng thời vô nghĩa). Đồng hồ là `MutableClock`, cổng thanh toán là `FakeGateway` (HTTP server của JDK),
identity-service là `FakeIdentity` (JWKS với khoá RSA sinh lúc chạy test, tra email). Tài khoản test nằm trong
`FakeIdentity`, không nằm trong database; `TestData.token` ký JWT RS256 như identity thật.

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
| Microservice: hợp đồng với notification-service | `TicketEventsContractIT`, `InternalApiIT` |
| Microservice: chỉ tin JWT do identity ký (RS256), từ chối khoá lạ, HS256, sai issuer | `JwtVerificationIT` |
| Microservice: identity sập → 502, không đổi dữ liệu; không dò được email | `IdentityOutageIT`, `JwksUnavailableIT` |

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
  phân công soát vé trả 502 `IDENTITY_UNAVAILABLE` và không đổi dữ liệu.
- **Bất biến dữ liệu**: `docker exec -i ticket2h-postgres-1 psql -U core < scripts/invariants.sql`.
- **Gọi API nội bộ bằng tay**: `curl -H "X-Internal-Api-Key: $INTERNAL_API_KEY_NOTIFICATION" 'localhost:8080/internal/tickets?ids=<ticketId>'`
  (khoá lấy trong `../infra/.env`; tra tài khoản giờ ở identity-service: `localhost:8082/internal/users/<userId>`).

## Cấu trúc

```
core-service/
├── src/main/java/com/ticket2h/
│   ├── common/        cấu hình, Clock, lỗi (ProblemDetail), kiểm tra JWT (JWKS) và khoá API nội bộ, event Kafka
│   ├── identity/      client gọi identity-service (tra tài khoản theo email); không còn lưu tài khoản
│   ├── catalog/       địa điểm, sự kiện, loại vé, sơ đồ ghế, tìm kiếm không dấu
│   ├── inventory/     giữ ghế, tồn kho GA, hạn mức mỗi user, sơ đồ ghế công khai
│   ├── ordering/      đơn hàng, idempotency, hết hạn, huỷ
│   ├── payment/       tạo thanh toán, webhook, hoàn tiền
│   ├── outbox/        ghi và phát outbox event, cấu hình Kafka
│   ├── ticketing/     phát vé, QR, check-in, chuyển nhượng; API nội bộ /internal/tickets
│   └── dev/           dữ liệu mẫu và endpoint chỉ có ở profile dev
├── src/main/resources/db/migration/   Flyway V1–V2 (M1), V100–V102 (M2), V110–V112 (M3), V113–V114 (tách notification, identity)
├── docs/                              api.md, schema.md, adr/ (001–010), huong-dan-hoc-code.md
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

## Việc để các mốc sau

- api-gateway: [infra/docs/kien-truc.md](https://github.com/ticket2h/infra/blob/main/docs/kien-truc.md), mục "Lộ trình tách tiếp".
- Job đối soát với MockPay khi webhook bị mất hẳn (`dropWebhook`): M6.
- Email vé vào DLT của notification-service (mail server sập quá 10 phút) phải gửi lại tay.
- Rate limit (đăng nhập, chuyển nhượng), phòng chờ, cache: M5.
- Bộ đếm GA một dòng là điểm nghẽn đã biết (ADR-003): đo bằng k6 ở M5.
