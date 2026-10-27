# ADR-027: Phòng chờ ảo bằng sorted set của Redis, vé vào cửa ký HMAC

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-27
- Liên quan: ADR-026 ở infra (giới hạn request), ADR-028 (giữ ghế bằng Redis), ADR-029 ở infra (load test), M5

## Bối cảnh
20:00 mở bán, 100.000 người vào cùng lúc; cho tất cả vào thẳng thì database sập (load test M5: với 2.000 người cùng
đặt vé, p95 của `POST /orders` đã quá 1 giây vì các đơn xếp hàng chờ khoá cùng mấy hàng ghế đầu). Lộ trình yêu cầu:
mọi người xếp hàng, mỗi giây cho N người vào mua, ai cũng thấy mình đứng thứ mấy, thứ tự giảm dần.

Câu hỏi:
- Hàng chờ nằm ở đâu, làm sao nhiều instance cùng cho người vào mà không vượt N mỗi giây, không cho trùng?
- API đặt vé làm sao biết người này đã được vào, mà không thêm một lần gọi Redis hay database lúc đông nhất?
- Công bằng với người đến trước giờ mở bán: ai bấm F5 lúc 19:59:59 có được đứng đầu không?

## Các phương án
**Hàng chờ.**
1. Bảng PostgreSQL: chính là thứ cần bảo vệ.
2. **Redis sorted set**: điểm là thứ tự, `ZRANK` cho vị trí, `ZPOPMIN n` lấy n người đầu, script Lua chạy nguyên khối.
3. Bộ đếm "số đang phục vụ" tăng theo thời gian (kiểu lấy số ở ngân hàng): không cần job, nhưng người đã rời đi vẫn
   chiếm lượt, và không bỏ họ ra được.

**Biết ai đã được vào.**
1. Tra Redis mỗi lần đặt vé: thêm một lần gọi mạng, và Redis sập thì không ai mua được nữa.
2. **Vé vào cửa có chữ ký và hạn ngắn** (HMAC-SHA256 trên sự kiện, người dùng, hạn): instance nào cũng kiểm tra được
   trong bộ nhớ.

## Quyết định
- Bật theo từng sự kiện: `events.waiting_room_admit_per_second` (V121), NULL là không có phòng chờ. Ban tổ chức đổi
  bằng `PUT /organizer/events/{id}/waiting-room`, được cả khi đang bán, có ghi lịch sử thao tác.
- Hàng chờ trong Redis, mọi khoá của một sự kiện cùng hash tag `{eventId}` (cùng node nếu sau này dùng Redis Cluster):
  `queue` (sorted set thứ tự), `seen` (lần cuối hỏi thứ tự), `admitted` (đã được vào, điểm là hạn), `seq`. Khoá tự
  hết hạn 1 giờ sau giờ đóng bán.
- Vào hàng (`POST /events/{id}/waiting-room`, gọi lại vẫn giữ chỗ): mở từ 30 phút trước giờ mở bán. Đến **trước giờ
  mở bán**: điểm ngẫu nhiên trong [0, 1), nên đứng trước mọi người đến sau và xếp ngẫu nhiên với nhau: bấm lúc
  19:59:59 không hơn bấm lúc 19:40. Đến sau giờ mở bán: điểm là số tăng dần.
- Mỗi giây, job ở **mọi** instance gọi một script Lua: `SET NX` khoá của giây đó (instance đến sau trong cùng giây
  dừng lại), bỏ những người quá 1 phút không hỏi thứ tự (đã đóng tab), `ZPOPMIN` N người, ghi họ vào `admitted` với
  hạn 10 phút. Nhiều instance cũng không vượt N mỗi giây.
- Hỏi thứ tự cũng là báo "vẫn đang chờ". BE trả `pollAfterSeconds` bằng khoảng nửa thời gian còn phải chờ, trong
  khoảng 2–15 giây (100.000 người hỏi mỗi 2 giây là 50.000 request mỗi giây chỉ để hỏi thứ tự). Thông tin sự kiện mà
  mỗi lần hỏi và mỗi đơn cần (có phòng chờ không, giờ bán) lấy qua cache làm mới ở nền 2 giây (ADR-025), không hỏi
  database mỗi lần: lần đo đầu, riêng việc này đưa PostgreSQL lên 2,5 CPU.
- Được vào: nhận `admissionToken` `v1.<sự kiện>.<người dùng>.<hạn>.<HMAC>` (khoá `WAITING_ROOM_SECRET`, mọi instance
  dùng chung). `POST /orders` của sự kiện có phòng chờ, trong giờ bán, cần header `Admission-Token`; thiếu, sai chữ ký,
  của người khác, sự kiện khác, hết hạn → 403 `ADMISSION_REQUIRED`. Kiểm tra trong bộ nhớ, so chữ ký thời gian hằng.
- Redis sập: vào hàng, hỏi thứ tự → 503 `WAITING_ROOM_UNAVAILABLE` (web thử lại, chỗ cũ còn khi Redis trở lại nếu
  Redis không mất dữ liệu); người đã có vé vào cửa vẫn đặt vé được. Sự kiện không bật phòng chờ không bị ảnh hưởng.

## Hệ quả và đánh đổi
- Được: database chỉ chịu N người đặt vé mỗi giây đã chọn trước; thêm instance core không làm vượt N.
- Mất: vé vào cửa dùng được nhiều lần trong 10 phút (để thử lại khi ghế vừa bị lấy); hạn mức vé mỗi người vẫn giới
  hạn số vé. Đổi `WAITING_ROOM_SECRET` thì mọi vé vào cửa đang có mất hiệu lực (người đó xếp hàng lại).
- Redis khởi động lại (không lưu xuống đĩa, xem infra) thì mất hàng chờ: mọi người xếp lại từ đầu. Chấp nhận được với
  sự cố hiếm; muốn giữ thì bật AOF cho Redis.
- Bot vẫn xếp hàng được bằng nhiều tài khoản: phòng chờ không chống bot, giới hạn request và xác minh tài khoản mới
  làm được việc đó.
- Chưa có SSE cho thứ tự (web hỏi theo `pollAfterSeconds`): đủ để "thấy thứ tự giảm dần", ít kết nối giữ mở hơn.

## Bằng chứng
`WaitingRoomIT` (acceptance, M5), 10 test: không có phòng chờ thì đặt thẳng; có phòng chờ thì đặt thẳng bị 403, vào
hàng theo thứ tự đến 1..5, vào lại giữ chỗ, được vào theo đúng N mỗi giây, thứ tự giảm dần, vé vào cửa của người khác
hay bị sửa chữ ký bị từ chối; job chạy hai lần trong cùng giây (hai instance) chỉ cho vào N người, giây đã qua không
dồn; 30 người đến trước giờ mở bán được xếp ngẫu nhiên và đứng trước người đến sau; quá 1 phút không hỏi thì mất chỗ;
vé vào cửa hết hạn sau 10 phút; 200 người vào cùng lúc có 200 vị trí khác nhau; ban tổ chức bật, tắt, xem số người
chờ, BTC khác 403, có lịch sử thao tác; hàng mở đúng 30 phút trước giờ bán; Redis tạm dừng: vào hàng 503, người đã có
vé vào cửa vẫn đặt được. web-app: `waitingRoom.test.ts`, `EventDetailPage.test.tsx` (phòng chờ).
