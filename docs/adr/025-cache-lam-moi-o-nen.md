# ADR-025: Cache trang sự kiện và sơ đồ ghế trong bộ nhớ, làm mới ở nền

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-27
- Liên quan: ADR-024 (sơ đồ ghế trực tiếp), ADR-029 ở infra (load test), M5 của lộ trình

## Bối cảnh
Lúc mở bán, hàng nghìn người cùng mở trang sự kiện và sơ đồ ghế. Mỗi lần mở là một truy vấn (sơ đồ 120.000 ghế là một
câu SELECT đọc 120.000 dòng rồi đổi sang 20MB JSON). Lộ trình yêu cầu cache thông tin sự kiện và sơ đồ ghế, và tránh
"cache stampede": cache hết hạn đúng lúc 20:00 khiến mọi request cùng đổ vào database.

Hai dữ liệu này đổi liên tục lúc mở bán (số vé còn, ghế vừa giữ), nhưng cũ vài giây không sai gì: đặt vé luôn kiểm
tra lại trong database, sơ đồ ghế có thay đổi qua SSE (ADR-024).

## Các phương án
1. Redis làm cache chung cho mọi instance. Một bản cho tất cả, nhưng mỗi request vẫn là một lần gọi mạng và một lần
   đọc 20MB; Redis thành chỗ nghẽn băng thông.
2. **Cache trong bộ nhớ của từng instance (Caffeine)**, thời hạn ngắn. Instance khác nhau có thể lệch nhau vài giây.

Cách hết hạn:
- a. `@Cacheable(sync = true)` với thời hạn 1–2 giây (đúng từ khoá của lộ trình). `sync` chặn được stampede: lúc hết
  hạn chỉ một request tải lại, các request khác của cùng khoá chờ kết quả đó. Nhưng chúng **chờ**: mỗi lần hết hạn,
  mọi request của khoá phải đợi một lần tải; lúc database bận (đúng giờ mở bán) lần tải đợi kết nối, và trang vốn đã
  có trong bộ nhớ cũng chậm theo.
- b. **`refreshAfterWrite` của Caffeine (làm mới ở nền, stale-while-revalidate)**: quá thời hạn thì request kế tiếp vẫn
  nhận ngay bản đang có, Caffeine tải bản mới trên luồng nền (mỗi khoá một lần tải). Chỉ lần đầu tiên mới phải chờ.

## Quyết định
Chọn 2b (`common/cache/RefreshAheadCache`, tạo qua `Caches`).
- Chi tiết sự kiện công khai, và sự kiện cho phòng chờ (ADR-027): làm mới sau 2 giây. Ảnh chụp sơ đồ ghế: làm mới sau 1 giây, giữ sẵn dạng JSON (byte) và
  `ETag` (MD5 của JSON): 1.000 người xem trong một giây dùng chung một lần truy vấn và một lần đổi sang JSON; trình
  duyệt gửi `If-None-Match`, không đổi thì nhận 304 không body.
- Tải lại lỗi (database chậm) thì giữ bản cũ; quá 30 giây (`max-stale`) chưa tải được thì bỏ bản cũ. Lỗi của lần tải
  đầu (404 sự kiện nháp) ném cho người gọi, không nhớ: công bố xong là thấy ngay.
- Giới hạn: tối đa 10.000 sự kiện; sơ đồ ghế giới hạn theo tổng kích thước JSON (64MB).
- Số liệu trúng/trượt/số lần tải ở `/actuator/metrics/cache.gets`, `cache.loads` (cho dashboard của M6).
- Profile test đặt thời hạn 0 (không cache) để test đọc lại được ngay sau khi đổi; `CacheStampedeIT` bật lại.

## Hệ quả và đánh đổi
- Được: database nhận tối đa một truy vấn mỗi sự kiện mỗi 1–2 giây ở mỗi instance, dù bao nhiêu người mở trang;
  request đọc không bao giờ phải chờ database sau lần đầu.
- Mất: dữ liệu cũ tối đa khoảng 1–2 giây (cộng thời gian tải); `onSale` của trang sự kiện có thể đổi trễ 2 giây quanh
  giờ mở bán (web tự đếm ngược theo `saleOpensAt`, BE vẫn kiểm tra lúc đặt). Instance không báo nhau khi dữ liệu đổi.
- Bài học khi đo (load test M5, ADR-029): bản đầu dùng `@Cacheable(sync = true)` đặt cùng `@Transactional` trên một
  method. Thứ tự hai advice không được khai báo, transaction chạy trước: **request trúng cache vẫn mượn một kết nối
  database**, nên lúc kết nối bận vì đơn đang chờ khoá ghế, trang sự kiện (trúng cache) có p95 hơn 2 giây. Bản này bỏ
  transaction ở phần đọc công khai (bốn câu SELECT độc lập, READ COMMITTED vốn cho mỗi câu một ảnh chụp riêng).
- "Làm nóng cache trước giờ mở bán" (từ khoá của lộ trình) không cần riêng: lần tải đầu đã gom mọi request cùng khoá
  thành một, sau đó cache tự làm mới ở nền.

## Bằng chứng
- `CacheStampedeIT`: 200 request cùng lúc vào sơ đồ ghế 500 ghế → 1 lần tải, 199 lần trúng, cùng `ETag`; 200 request
  vào trang sự kiện → 1 lần tải; sự kiện nháp 404 không bị nhớ.
- `RefreshAheadCacheTest`: quá thời hạn mà database đang chậm (500 ms) thì request vẫn nhận bản cũ dưới 100 ms, bản mới
  có sau đó; tải lại lỗi giữ bản cũ; lỗi lần đầu không bị nhớ.
- `SeatMapLiveIT`: `If-None-Match` khớp → 304.
