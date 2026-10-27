-- M6: đối soát thanh toán với cổng (ADR-022 trong docs/adr). Webhook có thể không bao giờ đến (cổng lỗi, mạng mất,
-- MockPay chế độ chaos "dropWebhook"): khách đã trả tiền mà đơn vẫn chờ, rồi hết hạn. Job đối soát hỏi lại cổng
-- trạng thái của các payment chưa có kết quả và xử lý như webhook vừa đến.
--
-- next_reconcile_at: lần hỏi cổng kế tiếp (null: không cần hỏi). reconcile_attempts: số lần cổng còn báo "đang chờ"
-- hoặc không trả lời. reconciled_at: đã có kết quả cuối cùng, không hỏi nữa.
ALTER TABLE payments
    ADD COLUMN next_reconcile_at   timestamptz,
    ADD COLUMN reconcile_attempts  integer NOT NULL DEFAULT 0,
    ADD COLUMN reconciled_at       timestamptz;

-- Payment chưa có kết quả từ trước migration này cũng được đối soát (dữ liệu sinh M4 chỉ có payment SUCCEEDED)
UPDATE payments SET next_reconcile_at = created_at + interval '2 minutes'
WHERE status IN ('INITIATED', 'FAILED');

-- Chỉ các payment còn phải hỏi: index nhỏ dù bảng có hàng triệu payment đã xong
CREATE INDEX idx_payments_reconcile_due ON payments (next_reconcile_at)
    WHERE reconciled_at IS NULL AND status IN ('INITIATED', 'FAILED');
