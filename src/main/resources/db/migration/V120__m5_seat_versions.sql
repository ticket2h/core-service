-- M5: số phiên bản của từng ghế (ADR-024), tăng mỗi lần ghế đổi trạng thái (giữ, trả, bán). Sơ đồ ghế trực tiếp gửi
-- kèm số này; trình duyệt chỉ áp thay đổi có số lớn hơn số nó đang có. Hai instance phát thay đổi của cùng một ghế theo
-- thứ tự ngược với thứ tự commit (luồng phát bị chậm) thì trình duyệt vẫn giữ trạng thái mới nhất.
--
-- PostgreSQL 11+ thêm cột NOT NULL có DEFAULT hằng số mà không ghi lại bảng: chạy tức thì kể cả với hàng triệu ghế.
ALTER TABLE seats ADD COLUMN version bigint NOT NULL DEFAULT 0;
