-- M5: phòng chờ ảo theo từng sự kiện (ADR-027). NULL: không có phòng chờ, ai cũng đặt vé thẳng. Có giá trị: trong giờ
-- mở bán mọi người xếp hàng (trong Redis), mỗi giây cho vào tối đa ngần ấy người; đặt vé phải kèm vé vào cửa có chữ ký.
-- Cột cho phép NULL, không có DEFAULT: thêm cột tức thì, dữ liệu cũ và công cụ sinh dữ liệu không phải đổi.
ALTER TABLE events ADD COLUMN waiting_room_admit_per_second integer;
ALTER TABLE events ADD CONSTRAINT events_waiting_room_rate_check
    CHECK (waiting_room_admit_per_second IS NULL OR waiting_room_admit_per_second BETWEEN 1 AND 10000);

-- Job mỗi giây tìm sự kiện có phòng chờ đang trong giờ bán: index chỉ chứa các sự kiện có phòng chờ (rất ít)
CREATE INDEX idx_events_waiting_room ON events (sale_opens_at) WHERE waiting_room_admit_per_second IS NOT NULL;
