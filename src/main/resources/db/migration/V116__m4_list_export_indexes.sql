-- M4: index cho danh sách đơn của ban tổ chức (phân trang keyset, ADR-018) và xuất danh sách khách (ADR-017)

-- Đơn của một sự kiện, mới trước. Trước M4 bảng orders chưa có index nào bắt đầu bằng event_id.
-- id đứng cuối để thứ tự là duy nhất: nhiều đơn tạo cùng một micro giây vẫn phân trang không lặp, không sót.
CREATE INDEX idx_orders_event_created ON orders (event_id, created_at DESC, id DESC);

-- Vé của một sự kiện theo thứ tự phát vé, để đọc từng lô 1.000 vé bằng keyset
CREATE INDEX idx_tickets_event_issued ON tickets (event_id, issued_at, id);
