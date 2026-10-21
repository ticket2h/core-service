# Ticket2h core-service

Lõi bán vé của Ticket2h: sự kiện, giữ ghế, đơn hàng, thanh toán, vé, check-in, chuyển nhượng. Tài khoản và JWT ở
`../identity-service`; core chỉ kiểm tra JWT (RS256, JWKS) và tra tài khoản (theo email, theo lô id) qua API nội bộ
của identity. Giao diện web ở `../web-app`; MockPay chuyển trình duyệt về `/payments/return` của web (kèm `orderId`).
Spring Boot 4, Java 25, PostgreSQL, Kafka. Một trong các repo của hệ thống (cạnh `../infra`, `../notification-service`,
`../mock-payment-gateway`, `../identity-service`); kiến trúc và hợp đồng giữa các service: `../infra/docs/`.
Hướng dẫn gốc (thời còn tên TicketRush): `../../ticketrush-m2-m3-huong-dan.md` (M2–M3), `../../lo-trinh-backend-java.md` (lộ trình, M1, M4).

## Lệnh
- Build và chạy toàn bộ test: `./mvnw verify` (cần Docker cho Testcontainers)
- Chỉ test một class: `./mvnw test -Dtest=RequestHashTest`, integration test:
  `./mvnw verify -Dit.test=CheckinIT -DskipExportMemoryIT=true` (không thì `ExportMemoryIT` cũng chạy, JVM `-Xmx256m`)
- Sinh dữ liệu lớn (M4): `../infra/scripts/datagen.sh`, hoặc profile `dev,datagen` (README, mục "Dữ liệu lớn")
- Hạ tầng và secret: trong `../infra`: `scripts/init-env.sh`, `docker compose up -d`
- Chạy với dữ liệu mẫu: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (cổng 8080)
- Demo xuyên các service: `../infra/scripts/demo.sh`

## Quy ước bắt buộc
- Thời gian: inject `Clock`, truyền `Instant` vào SQL (qua `Times.utc`). Không dùng `now()` của DB trong logic.
- Tiền: `BigDecimal`, so sánh bằng `compareTo`.
- Đổi trạng thái seat/ticket/quota/GA: SQL có điều kiện, kiểm tra số dòng bị ảnh hưởng.
  Order/payment: khoá bằng `FOR UPDATE` trước khi đổi. KHÔNG dùng `findById` → `if` → `save`.
- Thứ tự khoá: order → payment → purchase_quotas → ticket_types → seats (theo id tăng dần) → tickets.
  Mọi câu chạm nhiều ghế phải khoá `ORDER BY id FOR UPDATE` trước (`SeatHoldService.lockHeldSeats`), không UPDATE trần theo `order_id`.
- Khoá payment sau khi đã khoá order: lấy `order_id` bằng projection (`findOrderIdById`), không load entity trước khi khoá.
- Không gọi HTTP/gửi Kafka trong transaction. Event đi qua `OutboxWriter` (MANDATORY). `UserDirectory` gọi HTTP sang
  identity: dùng nó TRƯỚC khi mở transaction (mẫu `TicketTransferService` + `TicketTransferSteps`).
- Người gọi API: dùng `AuthUser` (id, role, organizerId từ JWT). Không hỏi identity những gì đã có trong token.
- Job xử lý theo lô: mỗi phần tử một transaction và try/catch riêng; một phần tử lỗi không chặn cả lô.
- Consumer Kafka phải idempotent. Payload thiếu trường → `InvalidEventPayloadException` (vào DLT ngay).
- Test: đơn vừa PAID có thể đã FULFILLED (relay + consumer chạy nền); assert `isIn("PAID", "FULFILLED")`, hoặc tắt relay như `CrashRecoveryIT`.
- Exception nghiệp vụ kế thừa `BusinessException` (RuntimeException), có `ErrorCode` (mã + HTTP status). Lỗi API là ProblemDetail có trường `code`.
- Migration Flyway: không sửa file đã merge, chỉ thêm file mới. Đặt tên cho mọi constraint.
- `Dockerfile`, `.github/workflows/ci.yml` giống nhau ở cả 4 repo service (ADR-014 trong `../infra/docs/adr/`): sửa một thì sửa cả bốn.
  Thêm biến môi trường mới thì thêm vào `.env.example` ở đây và `../infra` (`.env.example`, `scripts/init-env.sh`, `docker-compose.yml`).
- Không log QR token, secret, header Authorization, header `X-Internal-Api-Key`, email người dùng.
- Email không gửi từ đây: core ghi event vào outbox, notification-service gửi (ADR-011 trong `../infra/docs/adr/`).
- Event trên `ticket2h.ticket-events` và API `/internal/**` là hợp đồng với service khác (`../infra/docs/contracts.md`):
  đổi thì sửa tài liệu và notification-service cùng lúc; `TicketEventsContractIT`, `InternalApiIT` giữ hợp đồng.
- M4, báo cáo doanh thu (ADR-016): chỗ nào chuyển đơn sang PAID phải gọi `SalesRollup.enqueue` trong cùng transaction.
  Không cộng thẳng vào `sales_daily_*` lúc trả tiền (điểm nghẽn). Thêm kênh bán: sửa `SalesChannel` và ràng buộc
  `orders_sales_channel_check` bằng migration mới.
- Cột sắp xếp, cách gom, bộ lọc do người dùng chọn: nhận bằng enum, dịch sang đoạn SQL viết sẵn. Không ghép chuỗi
  người dùng gửi vào SQL.
- Danh sách có thể dài (đơn của sự kiện, file xuất): phân trang keyset theo `(thời gian, id)` (`KeysetCursor`, ADR-018),
  có index đúng thứ tự đó; không OFFSET, không đếm tổng mỗi trang.
- Xuất file lớn (ADR-017): không gom dòng vào `List`/chuỗi. Cần gọi identity giữa chừng thì đọc theo lô keyset, mỗi lô
  một câu SELECT tự commit (`AttendeeExport`); không gọi mạng thì đọc theo luồng trong transaction chỉ đọc với
  fetch size (`CheckinLogStore.stream`). File CSV ghi bằng `CsvWriter` (chặn công thức Excel), đọc bằng `CsvReader`.
- `checkin_logs` là bảng partition theo tháng (ADR-019): truy vấn nên có điều kiện trên `scanned_at` để chỉ đọc các tháng
  cần; khoá chính là `(id, scanned_at)`; không thêm khoá ngoại trỏ vào bảng này.
- `SecurityConfig` chỉ bật khi app chạy web (`@ConditionalOnWebApplication`): profile `datagen` chạy không có web.
- Thêm bảng hoặc cột NOT NULL mới: sửa cả công cụ sinh dữ liệu (`dev/datagen/CoreDataGenerator`) và chạy
  `CoreDataGeneratorIT`. Id của khách/ban tổ chức/nhân viên sinh ra là hợp đồng với identity (`DatagenIds`).

## Cấm
- Không sửa hoặc xoá test trong `src/test/**/acceptance/` nếu chưa hỏi. Test đỏ thì sửa code, không sửa test.
- Không thêm dependency mới nếu chưa hỏi.
- Không đổi thiết kế bảng trong `docs/schema.md` nếu chưa hỏi.

## Cấu trúc
Package theo nghiệp vụ dưới `com.ticket2h`: common, identity, catalog, inventory, ordering, payment, outbox, ticketing,
reporting, dev.
Module không gọi repository của module khác; gọi qua service public của module đó (`CatalogQueries`, `UserDirectory`, `OrderService`, `TicketQueries`...).
API cho service khác: `InternalTicketController` (`/internal/**`, xác thực bằng `InternalApiKeyFilter`). Gọi identity: `identity/IdentityClient` (qua `UserDirectory`).
Test dùng chung hạ tầng ở `src/test/java/com/ticket2h/support` (IntegrationTest, TestData, BulkData, MutableClock, Concurrency,
FakeGateway, FakeIdentity, KafkaTestMessages). `BulkData` sinh hàng trăm nghìn dòng ngay trong PostgreSQL (`generate_series`).
Tài khoản test tạo bằng `TestData.user(...)` (nằm trong `FakeIdentity`, không trong database); token bằng `TestData.token(id)`.
