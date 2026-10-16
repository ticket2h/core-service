# ADR-007: Outbox, Kafka, phát vé bất đồng bộ, chấp nhận email trùng

- Trạng thái: Đã chấp nhận. Phần email nay chạy ở notification-service, xem ADR-011 ([infra/docs/adr](https://github.com/ticket2h/infra/tree/main/docs/adr))
- Ngày: 2026-10-14

## Bối cảnh
Đã thanh toán thì chắc chắn phải có vé và email, kể cả khi app sập ngay sau khi commit hoặc Kafka đang không truy cập được.

## Các phương án
1. Gửi Kafka trong transaction (gửi xong mà rollback → phát vé cho đơn chưa trả)
2. Gửi Kafka sau commit (app sập trước dòng gửi → đơn PAID mãi không có vé)
3. Transactional outbox: INSERT `outbox_events` trong cùng transaction đổi trạng thái; relay đẩy lên Kafka
4. Phát vé luôn trong transaction webhook (không cần Kafka cho phát vé)

## Quyết định
Chọn phương án 3. `OutboxWriter` có `Propagation.MANDATORY` để không ai gọi được ngoài transaction. Relay chạy mỗi
500 ms, lấy lô 100 dòng `FOR UPDATE SKIP LOCKED`, gửi với key = `aggregate_id` (giữ thứ tự theo đơn), chờ Kafka xác
nhận rồi đánh dấu `published_at`; lỗi thì tăng `attempts`, ghi `last_error` và dừng lô.

Outbox đảm bảo **ít nhất một lần**, nên consumer phải xử lý trùng an toàn:
- Phát vé: khoá đơn; FULFILLED thì dừng; thêm vé bằng `ON CONFLICT (order_item_id, item_seq) DO NOTHING`.
- Email: `notification_deliveries` unique theo `dedupe_key`; đã SENT thì bỏ qua.

Phương án 4 đơn giản hơn và cũng không mất vé, nhưng ta tách ra để luyện outbox và consumer idempotent, và vì phát vé
thật thường có bước chậm. Gửi email thì bắt buộc bất đồng bộ.

Lỗi trong consumer: `DefaultErrorHandler` thử lại với backoff 0,5 giây nhân đôi, tối đa 30 giây mỗi lần, trong
**10 phút**, rồi đẩy sang `<topic>.DLT`. Message hỏng (`InvalidEventPayloadException`: không phải JSON, thiếu header,
thiếu trường bắt buộc) sang DLT ngay. Tên DLT ghi rõ trong code vì Spring Kafka 4 đổi hậu tố mặc định thành `-dlt`.

Bản đầu thử lại 5 lần (khoảng 15 giây). Review chỉ ra một lần DB failover 30 giây sẽ đẩy `OrderPaid` vào DLT và không
gì cứu lại: khách đã trả tiền mà không có vé. Nên thêm:
- Thời gian thử lại 10 phút cho lỗi tạm thời.
- `PaidOrderSweeper` (mỗi phút, khi `ticket2h.jobs.enabled`): đơn PAID quá 5 phút mà chưa FULFILLED thì gọi thẳng
  `issueFor` (idempotent). Đây là lưới an toàn cho message phát vé bị mất vì bất kỳ lý do gì.

## Hệ quả và đánh đổi
- Relay giữ transaction trong lúc chờ Kafka (tối đa 5 giây mỗi message, `max.block.ms = 5000`). Chấp nhận ở quy mô này.
- Hai instance relay có thể gửi hai event của cùng một đơn đảo thứ tự. Event của một đơn cách nhau xa và consumer kiểm
  tra trạng thái, nên không sao.
- **Email có thể trùng**: sập sau khi gửi nhưng trước khi đánh dấu SENT thì message được xử lý lại. Email trùng ít hại
  hơn email mất. Ngược lại, hoàn tiền phải "đúng một lần" nên dựa vào idempotency key phía cổng (ADR-006).
- Một message lỗi kéo dài chặn partition của nó tối đa 10 phút (thứ tự trong partition được giữ). Email gặp mail
  server sập quá 10 phút thì vào DLT và cần gửi lại tay; chưa có job quét email như `PaidOrderSweeper`.
- App bị `kill -9` thì consumer cũ còn trong group tới hết `session.timeout.ms`. Đặt 10 giây (mặc định 45 giây)
  để khởi động lại nhanh.

## Bằng chứng
- `CrashRecoveryIT`: relay tắt, thanh toán, "sập", chạy relay → vé xuất hiện; consumer lỗi lần đầu → vẫn đủ vé;
  message không phải JSON → nằm trong `ticket2h.order-events.DLT`, message sau vẫn được xử lý.
- Thử tay: `kill -9` app sau khi thanh toán (relay tắt) → khởi động lại → vé có sau 3 giây kể từ lúc app lên
  (trước khi giảm `session.timeout.ms`: 30 giây). Tạm dừng Kafka (`podman pause`) trong lúc thanh toán → đơn đứng ở PAID,
  outbox `attempts = 2`; chạy lại Kafka → FULFILLED sau 1 giây.
- `TicketIssuanceIT`, `TicketEmailIT`: message trùng không tạo thêm vé, không gửi thêm email; mail lỗi 2 lần vẫn gửi được.
- `CrashRecoveryIT.sweeper_issues_tickets_for_orders_paid_long_ago_without_tickets`: xoá outbox (message không bao giờ tới)
  → sau 5 phút job quét vẫn phát đủ vé; `event_with_missing_fields_goes_to_dlt_without_long_retries`.
