-- Câu SQL kiểm tra nhanh (phụ lục E1). Chạy trong core-service: docker exec -i ticket2h-postgres-1 psql -U core < scripts/invariants.sql

-- Không ghế nào thuộc hai vé còn hiệu lực (phải rỗng)
SELECT seat_id, count(*) FROM tickets
WHERE seat_id IS NOT NULL AND status <> 'VOID'
GROUP BY seat_id HAVING count(*) > 1;

-- Tồn kho GA khớp với số vé đã giữ hoặc bán: total_quantity = available_quantity + held_or_sold
SELECT tt.id, tt.total_quantity, tt.available_quantity,
       coalesce(sum(oi.quantity) FILTER (WHERE o.status IN ('PENDING_PAYMENT','PAID','FULFILLED')), 0) AS held_or_sold
FROM ticket_types tt
LEFT JOIN order_items oi ON oi.ticket_type_id = tt.id
LEFT JOIN orders o ON o.id = oi.order_id
WHERE tt.kind = 'GA'
GROUP BY tt.id;

-- Đơn PAID quá lâu mà chưa FULFILLED (outbox hoặc consumer có vấn đề)
SELECT id, paid_at FROM orders WHERE status = 'PAID' AND paid_at < now() - interval '5 minutes';

-- Outbox tồn đọng
SELECT count(*), min(created_at) FROM outbox_events WHERE published_at IS NULL;
