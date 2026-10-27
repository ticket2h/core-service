# ADR-024: Sơ đồ ghế trực tiếp bằng SSE, Redis pub/sub, số phiên bản của từng ghế

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-27
- Liên quan: ADR-002 (giữ ghế có số), ADR-025 (cache ảnh chụp sơ đồ ghế), ADR-029 ở infra (hai instance), M5 của lộ trình

## Bối cảnh
Trước M5 trang sự kiện tải lại sơ đồ ghế 15 giây một lần: người khác vừa giữ ghế thì mình vẫn thấy trống tới 15 giây,
bấm vào thì nhận `SEAT_UNAVAILABLE`. Lộ trình yêu cầu:
- Thay đổi hiện trên máy người khác trong vòng 1 giây.
- Vẫn đúng khi chạy 2 instance: trình duyệt nối vào instance A thấy thay đổi xảy ra ở instance B.
- 1.000 thay đổi mỗi giây thì gom lại gửi mỗi 300–500 ms, không gửi từng tin.
- Mất mạng rồi nối lại thì nhận đủ trạng thái hiện tại.

## Các phương án
**Kênh tới trình duyệt.**
1. WebSocket (STOMP). Hai chiều, nhưng sơ đồ ghế chỉ cần một chiều; cần thêm broker STOMP hoặc tự quản phiên, và
   gateway nginx phải nâng cấp kết nối.
2. **SSE (`text/event-stream`)**. Một chiều server → trình duyệt, là HTTP bình thường (qua nginx chỉ cần không gom bộ
   đệm), trình duyệt tự nối lại (`EventSource`), Spring MVC có sẵn `SseEmitter`.

**Kênh giữa các instance.**
1. Kafka: đã có, nhưng mỗi instance phải là một consumer group riêng (mỗi instance cần mọi message), độ trễ và cấu hình
   nặng hơn cho dữ liệu chỉ cần sống vài giây.
2. **Redis pub/sub**: phát tới mọi instance đang nghe, không lưu (mất thì thôi), độ trễ dưới mili giây.

**Thứ tự.** Hai instance phát thay đổi của cùng một ghế: A giữ ghế X, commit; B trả ghế X (huỷ đơn), commit. Luồng
phát của A chậm hơn B một chút thì trình duyệt nhận "trống" rồi mới "đã giữ": hiển thị sai cho tới lần tải lại sau.
1. Số thứ tự chung (Redis `INCR`): cho biết thứ tự phát, không phải thứ tự commit; không sửa được trường hợp trên.
2. **Số phiên bản của từng ghế trong database** (`seats.version`, tăng trong chính câu UPDATE đổi trạng thái): trình
   duyệt chỉ áp thay đổi có version lớn hơn cái nó đang có, nhận lẫn thứ tự vẫn ra trạng thái mới nhất.

## Quyết định
- `SeatHoldService`: mọi câu đổi trạng thái ghế (giữ, trả, bán) tăng `version` và `RETURNING id, section, status,
  version`; `SeatChangeRecorder` gom các ghế đổi trong transaction, phát **sau khi commit** (rollback thì không ai thấy
  thay đổi chưa từng xảy ra). Migration V120 thêm cột `version` (thêm cột có DEFAULT hằng số: không ghi lại bảng).
- `SeatChangeBus`: phát trên một luồng riêng (request không chờ Redis), gộp những gì đang chờ theo sự kiện. Mỗi message
  có số thứ tự theo sự kiện, tăng số và `PUBLISH` nằm trong một script Lua (Redis chạy nguyên khối): mọi instance nhận
  đúng thứ tự số.
- `SeatMapHub` (mỗi instance): gom thay đổi theo sự kiện, mỗi 300 ms gửi một event `seats` cho mỗi trình duyệt; trong
  một lô mỗi ghế chỉ giữ thay đổi có version lớn nhất. Mỗi kết nối gửi trên một luồng ảo riêng: trình duyệt chậm không
  làm chậm người khác; dồn quá 50 message thì đóng (trình duyệt nối lại). Keepalive 15 giây (gateway đóng kết nối im
  lặng sau 30 giây). Tối đa 20.000 kết nối mỗi instance, quá thì 503 và web quay về tải lại định kỳ.
- Ghép ảnh chụp với luồng: trình duyệt mở luồng TRƯỚC (`hello {seq}`), rồi tải `/seats`. Ảnh chụp đọc số thứ tự TRƯỚC
  khi đọc database và trả trong header `Seat-Map-Seq`: message số k chỉ được phát sau khi transaction của nó commit,
  nên mọi thay đổi có số ≤ `Seat-Map-Seq` đã nằm trong ảnh chụp. Ảnh chụp có số nhỏ hơn `hello` thì có thể thiếu thay
  đổi xảy ra giữa lúc chụp và lúc nối (ảnh chụp được cache tới 1 giây, ADR-025): web tải lại sau 1 giây. Ảnh chụp ghép
  với cái đang hiển thị theo version (cũ không đè mới).
- Mất message: Redis mất kết nối với một instance thì số thứ tự instance đó nhận được nhảy cóc; hub gửi `resync` để
  trình duyệt tải lại ảnh chụp. Nối lại SSE sau khi mất mạng cũng nhận `hello` mới, tức là lại tải đủ.
- Lưới an toàn: có SSE thì web vẫn tải lại ảnh chụp mỗi 60 giây (thay đổi phát lúc Redis sập không bao giờ tới).

## Hệ quả và đánh đổi
- Được: dưới 1 giây (300 ms gom + mạng), qua mọi instance; Redis sập thì bán vé vẫn chạy, sơ đồ quay về 15 giây một
  lần (web) hoặc 60 giây (đang có SSE nhưng không nhận được thay đổi).
- Mất: thêm một cột ghi trên bảng nóng nhất (cùng câu UPDATE, không thêm câu nào); mỗi instance nhận MỌI thay đổi của
  MỌI sự kiện (bỏ ngay nếu không ai xem sự kiện đó trên instance này), đủ dùng tới vài nghìn thay đổi mỗi giây.
- Chưa làm: sơ đồ theo khu. Sự kiện 120.000 ghế có ảnh chụp 20MB (nén còn 2MB): nên tải và nghe theo từng khu
  (kênh `...:{eventId}:{section}`), thay đổi đã mang `section` sẵn.
- Ghế giữ nhưng đã quá hạn hiện "trống" trong ảnh chụp (hết hạn lười, ADR-002) mà không có message nào; luồng chỉ báo
  khi job hết hạn trả ghế. Ảnh chụp cùng version thắng luồng nên không lệch lâu.

## Bằng chứng
`SeatMapLiveIT` (acceptance, M5), qua HTTP thật như trình duyệt:
- Ghế người khác giữ hiện dưới 1 giây (`HELD`, version 1), huỷ đơn thì hiện lại (`AVAILABLE`, version 2).
- 30 người đặt cùng lúc: đủ 30 ghế, không quá 10 lô.
- Ảnh chụp có version, `Seat-Map-Seq` không nhỏ hơn số của thay đổi vừa nhận, `ETag` và 304.
- **Hai instance thật** (test chạy thêm một app core cùng PostgreSQL, Kafka, Redis): giữ ghế ở A, trình duyệt ở B
  thấy; trả ghế ở B, trình duyệt ở A thấy.
- Số thứ tự nhảy cóc → `resync`; Redis bị tạm dừng → đặt vé vẫn dưới 1 giây, ảnh chụp vẫn đúng, Redis chạy lại thì
  luồng lại nhận thay đổi.
- web-app `liveSeats.test.ts`, `EventDetailPage.test.tsx`: thay đổi đến trễ, đến hai lần, ảnh chụp cũ không đè.
