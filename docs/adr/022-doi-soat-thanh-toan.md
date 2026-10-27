# ADR-022: Đối soát thanh toán với cổng khi webhook không đến

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-26
- Liên quan: ADR-006 (thanh toán trễ và hoàn tiền), M6 của lộ trình ("đơn kẹt ở trạng thái chờ thanh toán được job
  đối soát sửa")

## Bối cảnh
Đơn chuyển PAID khi và chỉ khi webhook `payment.succeeded` đến (`PaymentWebhookProcessor`). Webhook có thể không bao giờ
đến: cổng lỗi, mạng mất, cấu hình `notifyUrl` sai, hoặc chế độ chaos `dropWebhook` của MockPay. Khi đó khách đã bị trừ
tiền, đơn vẫn PENDING_PAYMENT, rồi job hết hạn trả ghế: khách mất tiền mà không có vé, và không ai hoàn tiền (chính sách
hoàn tiền ở ADR-006 chỉ chạy khi webhook đến trễ).

MockPay (như VNPay/MoMo) có API tra trạng thái theo mã đơn của người bán: `GET /api/v1/payments/by-ref/{merchantRef}`
(trạng thái `PENDING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`, mã giao dịch, số tiền). `merchantRef` của core là
id của payment.

## Các phương án
1. **Job cuối ngày quét mọi payment chưa có kết quả** (đúng chữ của lộ trình). Đơn giản, nhưng khách đã trả lúc 20:05
   phải chờ tới nửa đêm mới có vé, và lúc đó đơn đã hết hạn, ghế có thể đã bán cho người khác: chỉ còn hoàn tiền.
2. **Hỏi cổng ngay khi người dùng quay về trang `/payments/return`.** Nhanh cho ca thường gặp, nhưng không giúp khách
   đóng tab trước khi quay về, và để trình duyệt kích hoạt việc hỏi cổng (số lượng không kiểm soát được).
3. **Job chạy mỗi phút, mỗi payment có lịch hỏi riêng**: lần đầu 2 phút sau khi tạo (đủ để webhook bình thường đến
   trước), cổng còn "đang chờ" thì hỏi lại thưa dần, có kết quả cuối cùng thì thôi.

## Quyết định
Chọn phương án 3 (`PaymentReconciliation`, `PaymentReconciliationSteps`, migration V118).

- Ba cột mới trên `payments`: `next_reconcile_at` (lần hỏi kế tiếp), `reconcile_attempts`, `reconciled_at` (đã có kết
  quả, thôi hỏi). Ứng viên là payment `INITIATED` hoặc `FAILED` (FAILED vì có thể chỉ là ta timeout lúc tạo phiên) chưa
  có `reconciled_at` và đã tới `next_reconcile_at`. Index một phần chỉ trên các payment này: nhỏ dù bảng có hàng triệu
  payment đã xong.
- Lịch hỏi lại khi cổng còn `PENDING` hoặc không trả lời: 2, 3, 5, 10, 30 phút, 1, 3, 6, 12 giờ, rồi mỗi ngày, tối đa 15
  lần (khoảng một tuần) thì thôi và ghi log lỗi cho người xử lý.
- **Xử lý kết quả bằng đúng đường của webhook**: cổng báo `SUCCEEDED` thì dựng một event `payment.succeeded` (id
  `reconcile:<paymentId>`) và gọi `PaymentWebhookProcessor.process`. Cùng thứ tự khoá (đơn rồi payment), cùng cách chống
  trùng, cùng chính sách đơn đã hết hạn thì hoàn tiền, cùng event OrderPaid và hàng đợi báo cáo doanh thu. Webhook thật
  đến cùng lúc thì một bên ghi nhận, bên kia nhận `DUPLICATE`.
- `FAILED`, `CANCELLED` đi qua đường `payment.failed`; `EXPIRED` hoặc cổng không có phiên (404: lần tạo phiên chưa tới
  được cổng) thì payment INITIATED thành FAILED; cả ba là kết quả cuối cùng. Đơn vẫn chờ thanh toán (nếu còn hạn) để khách
  tạo phiên mới.
- Nhận việc bằng `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id`, đẩy `next_reconcile_at` ra
  sau 2 phút (lease, như `RefundSteps`): chạy nhiều instance không hỏi trùng, app sập giữa chừng thì payment được hỏi
  lại sau.
- Hỏi cổng ngoài transaction (quy ước "không gọi HTTP trong transaction"); mỗi payment một lần xử lý riêng, lỗi một
  payment không chặn cả lô.
- Cột `next_reconcile_at` được entity `Payment` ghi lúc tạo (`@Column(updatable = false)`), sau đó chỉ JDBC ghi; hai cột
  còn lại không có trong entity. Hibernate cập nhật cả dòng mỗi lần flush, nên cột mà JDBC quản lý không được nằm trong
  phần entity được phép ghi đè.
- Kết quả làm đổi trạng thái (PAID, chờ hoàn tiền) được ghi vào lịch sử thao tác với người làm
  `payment-reconciliation` (ADR-023).

## Hệ quả và đánh đổi
- Được: khách trả tiền mà webhook mất vẫn nhận vé sau khoảng 2–3 phút, trước khi giữ chỗ hết hạn (phiên thanh toán gia
  hạn giữ chỗ 15 phút). Đơn đã hết hạn thì tự hoàn tiền, không cần người xử lý.
- Mất: mỗi payment bình thường (webhook đến đúng) vẫn bị hỏi một lần nếu webhook đến sau mốc 2 phút; hầu hết webhook đến
  trong vài giây nên phần lớn payment đã SUCCEEDED trước lần hỏi đầu và không bị hỏi. Thêm tải lên API tra cứu của cổng:
  tối đa 50 payment mỗi phút mỗi instance.
- Cổng thật thường giới hạn số lần gọi API tra cứu: cần đặt `BATCH_SIZE` và lịch hỏi theo hạn mức của cổng.

## Bằng chứng
`PaymentReconciliationIT` (acceptance, M6), 9 test:
- Khách trả tiền, không có webhook: sau 2 phút đơn PAID, ghế SOLD, có vé (qua Kafka), có dòng hàng đợi doanh thu.
- Trước mốc 2 phút không hỏi cổng.
- Cổng tra cứu sập tới lúc đơn hết hạn: lần hỏi sau thấy đã trả tiền thì chuyển chờ hoàn tiền, tồn kho GA không bị trừ lại.
- Cổng còn "đang chờ": hỏi lại sau 2 phút; cổng lỗi một lần: lượt sau vẫn xử lý đúng.
- Webhook đến đúng lúc đang đối soát: một payment SUCCEEDED, một event OrderPaid, một dòng hàng đợi.
- Lần tạo phiên không tới cổng (404): payment FAILED, không hỏi lại; phiên hết hạn ở cổng: khách tạo được phiên mới.
- Payment đã có webhook thì không bao giờ bị hỏi.
