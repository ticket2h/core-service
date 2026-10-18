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

Mô tả máy đọc được (OpenAPI 3, springdoc): `GET /v3/api-docs`, giao diện thử ở `/swagger-ui.html`. Repo web sinh kiểu
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
| POST | `/organizer/events/{id}/staff` | ORGANIZER chủ sự kiện | `{email}` của tài khoản STAFF |
| DELETE | `/organizer/events/{id}/staff/{userId}` | ORGANIZER chủ sự kiện | |
| GET | `/events?q=&page=&size=` | Mọi người | Sự kiện đã công bố. `q` tìm không dấu: "son tung" ra "Sơn Tùng" |
| GET | `/events/{id}` | Mọi người | Chi tiết, loại vé, số vé còn |

## M2: đặt vé, thanh toán

| Method | Đường dẫn | Ai gọi | Ghi chú |
|---|---|---|---|
| GET | `/events/{eventId}/seats` | Mọi người | Trạng thái hiệu lực; không trả `order_id`, `held_until` |
| POST | `/orders` | CUSTOMER | Bắt buộc header `Idempotency-Key` (≤ 64 ký tự). 201 lần đầu; gửi lại cùng key và nội dung: 200 + `Idempotent-Replayed: true` |
| GET | `/orders/{orderId}` | Chủ đơn | Người khác nhận 404 |
| GET | `/me/orders` | Đã đăng nhập | 50 đơn gần nhất |
| POST | `/orders/{orderId}/cancel` | Chủ đơn | Chỉ khi `PENDING_PAYMENT` |
| POST | `/orders/{orderId}/payments` | Chủ đơn | Trả `paymentUrl`. 201 phiên mới, 200 phiên đang dùng được |
| GET | `/payments/return` | Trình duyệt | Chỉ hiển thị, không đổi trạng thái |
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
| `PAYLOAD_TOO_LARGE` | 413 | Webhook lớn hơn 64KB |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Cùng key nhưng nội dung khác |
| `INTERNAL_ERROR` | 500 | Lỗi không lường trước (đã log) |
| `PAYMENT_GATEWAY_UNAVAILABLE` | 502 | Gọi MockPay thất bại hoặc quá thời gian |
| `IDENTITY_UNAVAILABLE` | 502 | identity-service không trả lời: lúc tra email (chuyển nhượng, phân công soát vé), hoặc lúc core chưa lấy được public key để kiểm tra token |

`INVALID_CREDENTIALS`, `EMAIL_ALREADY_REGISTERED` nay do identity-service trả.

## API nội bộ (cho service khác, không cho người dùng)

| Method | Đường dẫn | Ai gọi | Ghi chú |
|---|---|---|---|
| GET | `/internal/tickets?ids=` | notification-service | 1–50 id; vé kèm `qrToken` nếu VALID; `Cache-Control: no-store` |

Xác thực bằng header `X-Internal-Api-Key` (khoá riêng của từng service, cấu hình `ticket2h.internal-api.clients`).
JWT của người dùng không mở được các API này. Đặc tả đầy đủ và quy tắc thay đổi: [infra/docs/contracts.md](https://github.com/ticket2h/infra/blob/main/docs/contracts.md).
