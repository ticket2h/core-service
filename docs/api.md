# API

Mọi lỗi trả theo RFC 9457 (`application/problem+json`) kèm trường `code`:

```json
{
  "type": "https://ticket2h.dev/errors/seat-unavailable",
  "title": "Ghế không còn trống",
  "status": 409,
  "detail": "Có ghế đã được người khác giữ",
  "code": "SEAT_UNAVAILABLE",
  "unavailableSeatIds": ["..."]
}
```

Xác thực: `Authorization: Bearer <accessToken>` lấy từ `POST /auth/login` của **identity-service** (cổng 8082).
Token ký RS256, chứa `sub` (userId), `role`, và `organizerId` với tài khoản ORGANIZER. core chỉ kiểm tra token
(public key lấy từ JWKS của identity), không phát token.

Mô tả máy đọc được (OpenAPI 3, springdoc): `GET /v3/api-docs`, giao diện thử ở `/swagger-ui.html`. Repo web-app sinh kiểu
TypeScript từ đây (`npm run api:types`).

Đăng ký, đăng nhập, `GET /me`, `/admin/organizers`, `/admin/users` nay ở identity-service: xem
[identity-service README](https://github.com/ticket2h/identity-service#readme).

## M1: sự kiện

| Method | Đường dẫn | Ai gọi | Ghi chú |
|---|---|---|---|
| POST/GET | `/organizer/venues` | ORGANIZER | Địa điểm của ban tổ chức mình |
| POST/GET | `/organizer/events` | ORGANIZER | Tạo ở trạng thái DRAFT. `{venueId, title, description?, startsAt, saleOpensAt, saleClosesAt, maxTicketsPerUser?}` |
| GET/PUT | `/organizer/events/{id}` | ORGANIZER chủ sự kiện | Sự kiện của BTC khác → 403 |
| POST | `/organizer/events/{id}/ticket-types` | ORGANIZER chủ sự kiện | `{name, kind: SEATED\|GA, price, quantity (GA)}`. Chỉ khi DRAFT |
| POST | `/organizer/events/{id}/seats` | ORGANIZER chủ sự kiện | `{ticketTypeId, section, rows: [{label, from, to}]}`. Chỉ khi DRAFT |
| POST | `/organizer/events/{id}/publish` | ORGANIZER chủ sự kiện | Cần ít nhất một loại vé; loại SEATED phải có ghế |
| GET | `/organizer/events/{id}/staff` | ORGANIZER chủ sự kiện | `[{userId, email, fullName}]`, sắp theo tên rồi email. Email, tên lấy từ identity; tài khoản đã bị xoá: `email`, `fullName` là `null` (xếp cuối, vẫn gỡ được) |
| POST | `/organizer/events/{id}/staff` | ORGANIZER chủ sự kiện | `{email}` của tài khoản STAFF → 201 `{userId, email, fullName}` |
| DELETE | `/organizer/events/{id}/staff/{userId}` | ORGANIZER chủ sự kiện | |
| GET | `/events?q=&page=&size=` | Mọi người | Sự kiện đã công bố. `q` tìm không dấu: "son tung" ra "Sơn Tùng" |
| GET | `/events/{id}` | Mọi người | Chi tiết, loại vé, số vé còn |

## M2: đặt vé, thanh toán

| Method | Đường dẫn | Ai gọi | Ghi chú |
|---|---|---|---|
| GET | `/events/{eventId}/seats` | Mọi người | Trạng thái hiệu lực; không trả `order_id`, `held_until` |
| POST | `/orders` | CUSTOMER | Bắt buộc header `Idempotency-Key` (≤ 64 ký tự). 201 lần đầu; gửi lại cùng key và nội dung: 200 + `Idempotent-Replayed: true`. `channel` (M4, tuỳ chọn): `WEB` (mặc định) hoặc `MOBILE_APP`, cho báo cáo doanh thu |
| GET | `/orders/{orderId}` | Chủ đơn | Người khác nhận 404 |
| GET | `/me/orders` | Đã đăng nhập | 50 đơn gần nhất |
| POST | `/orders/{orderId}/cancel` | Chủ đơn | Chỉ khi `PENDING_PAYMENT` |
| POST | `/orders/{orderId}/payments` | Chủ đơn | Trả `paymentUrl`. 201 phiên mới, 200 phiên đang dùng được. Xong trang MockPay, trình duyệt về `return-url?orderId=…&merchantRef=<paymentId>&status=…` (mặc định `/payments/return` của web-app) |
| GET | `/payments/return` | Trình duyệt | Trang dự phòng khi `ticket2h.payment.mockpay.return-url` trỏ về core. Chỉ hiển thị, không đổi trạng thái |
| POST | `/webhooks/payments/mockpay` | MockPay | Không cần JWT, bắt buộc chữ ký hợp lệ |

## M3: vé, soát vé

| Method | Đường dẫn | Ai gọi | Ghi chú |
|---|---|---|---|
| GET | `/me/tickets` | Đã đăng nhập | Vé đang sở hữu |
| GET | `/tickets/{ticketId}` | Chủ vé | |
| GET | `/tickets/{ticketId}/qr` | Chủ vé | Ảnh PNG, `Cache-Control: no-store`. Vé không còn VALID → 409 |
| POST | `/tickets/{ticketId}/transfer` | Chủ vé | `{recipientEmail}` của tài khoản CUSTOMER |
| POST | `/checkin` | STAFF được phân công | `{qrToken, gate}` → 200 với `result`: `OK`, `ALREADY_USED`, `TOKEN_REVOKED`, `VOIDED`, `INVALID_TOKEN`; không được phân công → 403 |
| GET | `/ticket-keys` | Máy soát vé | `{activeKeyId, algorithm, keys: {keyId: base64}}` |
| GET | `/dev/tickets/{ticketId}/qr-token` | Chủ vé, **chỉ profile dev** | Chuỗi token QR để thử check-in bằng curl |

## M4: báo cáo, danh sách lớn, file

| Method | Đường dẫn | Ai gọi | Ghi chú |
|---|---|---|---|
| GET | `/organizer/events/{id}/sales?groupBy=&sort=&direction=&from=&to=` | ORGANIZER chủ sự kiện | Doanh thu từ bảng tổng hợp (chậm hơn thực tế tối đa ~5 giây, ADR-016). `groupBy`: `DAY` (mặc định, ngày theo múi giờ sự kiện), `TICKET_TYPE`, `CHANNEL`. `sort`: `KEY`, `ORDERS`, `TICKETS`, `REVENUE`; `direction`: `ASC`, `DESC`; giá trị khác → 400. Trả `totals {orders, tickets, revenue}` và `rows [{key, label, orders, tickets, revenue}]`; `orders` là `null` khi gom theo loại vé |
| GET | `/organizer/events/{id}/orders?status=&limit=&cursor=` | ORGANIZER chủ sự kiện | Đơn mới trước, phân trang keyset (ADR-018): trang sau gửi lại `nextCursor`; `nextCursor: null` là hết. `limit` 1–100 (mặc định 20). Mỗi đơn kèm `buyer {fullName, email}` (hỏi identity theo lô; `null` khi tài khoản không còn). Không có tổng số |
| GET | `/organizer/events/{id}/attendees.csv` | ORGANIZER chủ sự kiện | Danh sách khách, mỗi vé còn hiệu lực một dòng, UTF-8 có BOM. Đọc theo lô 1.000 vé (ADR-017): 500.000 dòng với heap 256MB. Lỗi trước dòng đầu (403, 502...) vẫn là JSON; lỗi giữa chừng thành dòng `# LỖI` cuối file |
| GET | `/organizer/events/{id}/checkins.csv?from=&to=` | ORGANIZER chủ sự kiện | Lịch sử soát vé trong khoảng ngày (theo múi giờ sự kiện, tối đa 31 ngày; mặc định ngày diễn ±1). Đọc theo luồng bằng cursor của PostgreSQL (fetch size) |
| POST | `/organizer/events/{id}/seats/import` | ORGANIZER chủ sự kiện | Body là file CSV (`Content-Type: text/csv`, UTF-8, ≤ 5MB, ≤ 100.000 ghế), tiêu đề `loai_ve,khu,hang,so_ghe`. 201 `{created, ticketTypes: [{ticketType, created}]}`. Có lỗi thì không thêm ghế nào: 422 `SEAT_IMPORT_INVALID` (ADR-020). Chỉ khi DRAFT |

## Mã lỗi

| `code` | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Body sai: thiếu trường, trùng ghế, vừa có `seatIds` vừa có `quantity`, JSON hỏng… |
| `IDEMPOTENCY_KEY_MISSING` | 400 | Thiếu header `Idempotency-Key` |
| `UNAUTHENTICATED` | 401 | Thiếu hoặc sai JWT |
| `INVALID_WEBHOOK_SIGNATURE` | 401 | Webhook sai chữ ký hoặc quá hạn |
| `FORBIDDEN` | 403 | Sai vai trò; sự kiện của BTC khác; nhân viên không được phân công |
| `NOT_FOUND` | 404 | Không tồn tại **hoặc không phải của bạn** |
| `RECIPIENT_NOT_FOUND` | 404 | Chuyển nhượng cho email không có tài khoản khách hàng |
| `EVENT_NOT_EDITABLE` | 409 | Thêm loại vé/ghế cho sự kiện đã công bố |
| `EVENT_NOT_PUBLISHABLE` | 409 | Công bố khi chưa có loại vé, chưa có ghế, hoặc đã hết giờ bán |
| `SALE_NOT_OPEN` | 409 | Ngoài giờ mở bán |
| `SEAT_UNAVAILABLE` | 409 | Có ghế đã được giữ hoặc đã bán (kèm `unavailableSeatIds`) |
| `SOLD_OUT` | 409 | Không đủ vé GA |
| `PURCHASE_LIMIT_EXCEEDED` | 409 | Vượt số vé tối đa mỗi user |
| `ORDER_NOT_PAYABLE` | 409 | Đơn đã hết hạn, đã huỷ hoặc đã trả |
| `ORDER_NOT_CANCELLABLE` | 409 | Huỷ đơn không còn ở `PENDING_PAYMENT` |
| `PAYMENT_ATTEMPTS_EXCEEDED` | 409 | Tạo quá nhiều phiên thanh toán (mặc định 3) |
| `TICKET_NOT_TRANSFERABLE` | 409 | Vé đã dùng, trong vòng 2 giờ trước giờ diễn… |
| `TICKET_NOT_VALID` | 409 | Lấy QR của vé đã dùng hoặc đã huỷ |
| `PAYLOAD_TOO_LARGE` | 413 | Webhook lớn hơn 64KB; file ghế lớn hơn 5MB |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Cùng key nhưng nội dung khác |
| `SEAT_IMPORT_INVALID` | 422 | File ghế có lỗi: kèm `errors` (tối đa 200 lỗi đầu, mỗi lỗi `{line, message}`, sắp theo dòng) và `errorCount` (tổng số lỗi). Không ghế nào được thêm |
| `INTERNAL_ERROR` | 500 | Lỗi không lường trước (đã log) |
| `PAYMENT_GATEWAY_UNAVAILABLE` | 502 | Gọi MockPay thất bại hoặc quá thời gian |
| `IDENTITY_UNAVAILABLE` | 502 | identity-service không trả lời: lúc tra tài khoản (chuyển nhượng, phân công, danh sách nhân viên, danh sách đơn, file danh sách khách), hoặc lúc core chưa lấy được public key để kiểm tra token |

`INVALID_CREDENTIALS`, `EMAIL_ALREADY_REGISTERED` nay do identity-service trả.

## API nội bộ (cho service khác, không cho người dùng)

| Method | Đường dẫn | Ai gọi | Ghi chú |
|---|---|---|---|
| GET | `/internal/tickets?ids=` | notification-service | 1–50 id; vé kèm `qrToken` nếu VALID; `Cache-Control: no-store` |

Xác thực bằng header `X-Internal-Api-Key` (khoá riêng của từng service, cấu hình `ticket2h.internal-api.clients`).
JWT của người dùng không mở được các API này. Đặc tả đầy đủ và quy tắc thay đổi: [infra/docs/contracts.md](https://github.com/ticket2h/infra/blob/main/docs/contracts.md).
