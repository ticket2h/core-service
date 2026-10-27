# Ticket2h: đọc hiểu code cho người mới học Spring

Tài liệu này dành cho người **đã biết Java core** (class, interface, record, enum, exception, `List`/`Map`, lambda cơ bản)
nhưng **chưa làm Spring**. Nó đi từ bức tranh tổng thể, qua các khái niệm Spring Boot, rồi giải thích từng chức năng
của Ticket2h bằng chính code trong repo.

Mọi đường dẫn file tính từ `src/main/java/com/ticket2h/` nếu không ghi khác. Code trích ở đây đôi khi được
rút gọn (`...`) để dễ đọc; mở file thật để xem đầy đủ.

> **Cập nhật 2026-10-21: M4 (dữ liệu lớn)** có ở mục 12: báo cáo doanh thu, phân trang keyset, xuất file 500.000 dòng,
> nhập 50.000 ghế, partition bảng log, công cụ sinh 10 triệu đơn.
>
> **Cập nhật 2026-10-16: hệ thống bắt đầu tách thành microservice.** Tài liệu này viết khi mọi thứ còn nằm trong
> một ứng dụng (TicketRush M1–M3). Ứng dụng đó nay là **core-service** (repo này). Phần **gửi email** đã chuyển sang
> repo `notification-service`, MockPay thành repo `mock-payment-gateway`. Hầu hết nội dung vẫn đúng, chỉ khác:
> - Code email (mục 11.6) nằm ở [notification-service](https://github.com/ticket2h/notification-service): cùng logic, thêm bước gọi API nội bộ để lấy email người
>   nhận (từ identity) và token QR (từ core).
> - **Bước 2 (cùng ngày):** tài khoản, đăng nhập, phát JWT (mục 6.2, 6.3, 7.1) chuyển sang repo `identity-service`.
>   core chỉ còn **kiểm tra** JWT, nay ký RS256 thay vì HS256, public key lấy qua JWKS. Không còn bảng `users`,
>   `organizers` trong database `core`.
> - Repo có một `pom.xml`, không còn module `app`: chạy `./mvnw ...`, không có `-pl app` (mục 3.1).
> - Package, topic và cấu hình đổi tên từ `ticketrush` sang `ticket2h`. Database tên `core`, user `core`.
> - Hạ tầng (`docker compose`), file `.env` và `scripts/demo.sh` nằm ở repo [infra](https://github.com/ticket2h/infra) (trên máy thường clone cạnh repo này: `../infra`).
>
> Bức tranh cả hệ thống: [infra/docs/kien-truc.md](https://github.com/ticket2h/infra/blob/main/docs/kien-truc.md). Hợp đồng giữa các service: [infra/docs/contracts.md](https://github.com/ticket2h/infra/blob/main/docs/contracts.md).

## Mục lục

0. [Cách dùng tài liệu](#0-cách-dùng-tài-liệu)
1. [Bức tranh tổng thể](#1-bức-tranh-tổng-thể)
2. [Spring Boot từ con số 0](#2-spring-boot-từ-con-số-0)
3. [Cấu trúc project](#3-cấu-trúc-project)
4. [Database: PostgreSQL, Flyway, JPA và JDBC](#4-database-postgresql-flyway-jpa-và-jdbc)
5. [Tầng web: controller, DTO, validation, xử lý lỗi](#5-tầng-web-controller-dto-validation-xử-lý-lỗi)
6. [Bảo mật: đăng nhập JWT và phân quyền](#6-bảo-mật-đăng-nhập-jwt-và-phân-quyền)
7. [M1: tài khoản, sự kiện, tìm kiếm không dấu](#7-m1-tài-khoản-sự-kiện-tìm-kiếm-không-dấu)
8. [Xử lý đồng thời: phần quan trọng nhất](#8-xử-lý-đồng-thời-phần-quan-trọng-nhất)
9. [M2: tạo đơn, idempotency, hết hạn, huỷ](#9-m2-tạo-đơn-idempotency-hết-hạn-huỷ)
10. [M2: thanh toán, webhook, hoàn tiền](#10-m2-thanh-toán-webhook-hoàn-tiền)
11. [M3: outbox, Kafka, vé, QR, email, check-in, chuyển nhượng](#11-m3-outbox-kafka-vé-qr-email-check-in-chuyển-nhượng)
12. [M4: dữ liệu lớn](#12-m4-dữ-liệu-lớn)
13. [M5: thời gian thực và giờ mở bán](#13-m5-thời-gian-thực-và-giờ-mở-bán)
14. [M6: đối soát thanh toán, lịch sử thao tác](#14-m6-đối-soát-thanh-toán-lịch-sử-thao-tác)
15. [Test](#15-test)
16. [Bài tập thực hành và câu hỏi tự kiểm tra](#16-bài-tập-thực-hành-và-câu-hỏi-tự-kiểm-tra)
17. [Thuật ngữ](#17-thuật-ngữ)

---

## 0. Cách dùng tài liệu

### Đọc theo thứ tự nào

Đừng mở file ngẫu nhiên. Đi theo 6 chặng, mỗi chặng chạy được và thấy kết quả:

| Chặng | Đọc mục | Mở các file | Bạn sẽ hiểu |
|---|---|---|---|
| 1 | 1, 2, 3 | `CoreServiceApplication`, `application.yml`, `pom.xml` | App khởi động thế nào, Spring "nối dây" các class ra sao |
| 2 | 4, 5, 6 | `identity/*`, `common/error/*`, `common/security/*` | Một request HTTP đi qua những lớp nào |
| 3 | 7 | `catalog/*` | CRUD có kiểm tra quyền, SQL thuần |
| 4 | 8, 9, 10 | `inventory/*`, `ordering/*`, `payment/*` | Bán vé đúng khi hàng nghìn người cùng bấm |
| 5 | 11, 13 | `outbox/*`, `ticketing/*`, [notification-service](https://github.com/ticket2h/notification-service), `src/test/**` | Hệ thống nhiều thành phần, và cách test nó |
| 6 | 12 | `dev/datagen/*`, `reporting/*`, `ordering/OrderListStore`, `ticketing/*Export*`, `catalog/SeatImport` | Cái gì còn nhanh khi có 10 triệu đơn, và cách đo |

### Vừa đọc vừa chạy

Đọc code mà không chạy thì rất nhanh quên. Ba cách quan sát code đang chạy:

1. **Debugger trong IntelliJ.** Đặt breakpoint (bấm vào lề trái cạnh số dòng), chạy app bằng nút con bọ 🐞 thay vì ▶.
   Gọi API bằng `../infra/scripts/demo.sh`, app sẽ dừng ở breakpoint, bạn xem giá trị từng biến. Nên bắt đầu ở
   `OrderController.create` (`ordering/OrderController.java`).
2. **DBeaver.** Sau mỗi bước của demo, xem bảng `orders`, `seats`, `payments`, `tickets`, `outbox_events` thay đổi ra sao.
3. **Test.** Mỗi chức năng có test mô tả hành vi bằng code. Đọc tên test như đọc đặc tả, ví dụ
   `only_one_of_200_concurrent_buyers_gets_the_same_seat`.

Tài liệu gốc của dự án: `ticketrush-m2-m3-huong-dan.md` (thiết kế từng bước, tài liệu riêng, không nằm trong repo) và `docs/adr/` (vì sao chọn cách làm).

---

## 1. Bức tranh tổng thể

### Ticket2h làm gì

Một hệ thống bán vé sự kiện (concert, show). Bài toán khó không phải là "lưu một đơn hàng" mà là:

- 200 người cùng bấm một ghế đẹp → chỉ đúng **1** người được.
- 1.000 vé đứng (GA), 10.000 người tranh → bán đúng **1.000**, không hơn.
- Khách bấm "mua" hai lần → chỉ có **một** đơn.
- Cổng thanh toán báo "đã trả tiền" 3 lần → chỉ ghi nhận **một** lần.
- App sập ngay sau khi khách trả tiền → khởi động lại khách **vẫn** có vé.
- QR vé không làm giả được, một vé chỉ vào cửa được **một** lần.

### Bốn vai trò

| Vai trò | Làm gì |
|---|---|
| `CUSTOMER` (khách) | Tìm sự kiện, mua vé, thanh toán, xem vé, chuyển vé cho bạn |
| `ORGANIZER` (ban tổ chức, BTC) | Tạo địa điểm, sự kiện, loại vé, sơ đồ ghế, phân công nhân viên soát vé |
| `STAFF` (nhân viên soát vé) | Quét QR ở cổng |
| `ADMIN` | Tạo ban tổ chức và tài khoản nhân viên |

### Các thành phần

```
   Trình duyệt / curl / app mobile
              │  HTTP + JSON (JWT trong header Authorization)
              ▼
   ┌───────────────────────┐   tạo phiên thanh toán    ┌──────────────────────┐
   │     core-service      │ ────────────────────────► │  MockPay (cổng 9090) │
   │   Spring Boot :8080   │ ◄──────────────────────── │  cổng thanh toán giả │
   └───┬──────────┬────────┘   webhook "đã trả tiền"   └──────────────────────┘
       │          │      ▲               ▲
       │ SQL      │ gửi  │ nhận event    │ GET /internal/** (lấy email, token QR)
       ▼          ▼      │               │
  PostgreSQL     Kafka ──┴──────► notification-service :8081 ──► Mailpit (SMTP giả,
  (database   (hàng đợi event)    (database "notification")        xem email ở :8025)
   "core")
```

- **core-service**: lõi nghiệp vụ, chia **module theo nghiệp vụ** bên trong (mục 3.2).
- **notification-service**: nghe event vé trên Kafka, gửi email có QR. Service riêng, database riêng.
- **mock-payment-gateway (MockPay)**: giả lập cổng thanh toán như VNPay/MoMo, có "chaos mode" để thử tình huống xấu
  (gửi webhook trùng, trễ, mất).
- **PostgreSQL**: nơi lưu dữ liệu, cũng là "trọng tài" quyết định ai thắng khi tranh nhau.
- **Kafka**: hàng đợi event. Khi đơn đã thanh toán, core không phát vé ngay mà đẩy event `OrderPaid` vào Kafka;
  một phần khác của core nhận event rồi phát vé, rồi đẩy tiếp `TicketsIssued` cho notification-service gửi email.
- **Mailpit**: hộp thư giả để xem email mà không gửi ra ngoài thật.

### Hành trình mua một vé

| # | Việc | Endpoint / thành phần | Class chính |
|---|---|---|---|
| 1 | Khách tìm sự kiện, xem sơ đồ ghế | `GET /events?q=son tung`, `GET /events/{id}/seats` | `PublicEventController`, `SeatMapController` |
| 2 | Khách tạo đơn: giữ ghế/vé GA trong 10 phút | `POST /orders` + header `Idempotency-Key` | `OrderPlacement` → `OrderCreationService` |
| 3 | Khách bấm thanh toán, nhận link MockPay | `POST /orders/{id}/payments` | `PaymentInitiation` |
| 4 | Khách trả tiền trên trang MockPay | MockPay | |
| 5 | MockPay gọi webhook có chữ ký → đơn `PAID`, ghế `SOLD` | `POST /webhooks/payments/mockpay` | `WebhookIngestion` → `PaymentWebhookProcessor` |
| 6 | Event `OrderPaid` đi qua outbox → Kafka → phát vé | Kafka topic `ticket2h.order-events` | `OutboxBatchPublisher`, `TicketIssuer`, `TicketIssuance` |
| 7 | Event `TicketsIssued` → notification-service gửi email có QR | Kafka topic `ticket2h.ticket-events`, `GET /internal/tickets` | `TicketEventsListener`, `TicketNotificationService` (repo notification-service) |
| 8 | Nhân viên quét QR ở cổng → vé `USED` | `POST /checkin` | `CheckinService` |

Chạy `../infra/scripts/demo.sh` là đi đúng hành trình này. Mở file script ra đọc: mỗi bước là một lệnh `curl`.

---

## 2. Spring Boot từ con số 0

### 2.1. Vấn đề mà Spring giải quyết

Trong Java thuần, muốn dùng một object thì bạn `new` nó, và tự `new` luôn mọi thứ nó cần:

```java
// Java thuần: tự tay lắp ráp
DataSource ds = new HikariDataSource(...);
NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
SeatHoldService seatHolds = new SeatHoldService(jdbc);
GaInventory ga = new GaInventory(jdbc);
PurchaseQuotas quotas = new PurchaseQuotas(jdbc);
OrderCreationService creation = new OrderCreationService(clock, props, catalog, pricing,
        orders, orderItems, quotas, ga, seatHolds);
// ... hàng trăm dòng như vậy cho cả app
```

App có hơn 100 class phụ thuộc lẫn nhau, tự lắp như vậy là không thể. **Spring** làm việc lắp ráp đó cho bạn:

- Bạn đánh dấu class bằng annotation (`@Service`, `@Component`...).
- Khi khởi động, Spring quét các class đó, tự tạo **một** object cho mỗi class, và tự truyền các object vào nhau.
- Object do Spring quản lý gọi là **bean**. Nơi Spring giữ các bean gọi là **ApplicationContext** (hay IoC container).

Hai cái tên bạn sẽ gặp: **IoC** (Inversion of Control: bạn không tự tạo object, Spring tạo) và **DI** (Dependency
Injection: Spring "tiêm" các object cần thiết vào).

### 2.2. Dependency Injection qua constructor

Đây là cách dùng xuyên suốt project. Xem `ordering/OrderCancellation.java`:

```java
@Service                                   // (1) "Spring ơi, hãy tạo một bean từ class này"
public class OrderCancellation {

    private final OrderRepository orders;  // (2) những thứ class này cần
    private final OrderRelease release;
    private final Clock clock;

    OrderCancellation(OrderRepository orders, OrderRelease release, Clock clock) {  // (3)
        this.orders = orders;
        this.release = release;
        this.clock = clock;
    }

    @Transactional
    public Order cancel(UUID userId, UUID orderId) { ... }
}
```

Ở (3), constructor khai báo nó cần 3 thứ. Spring thấy class chỉ có một constructor, nên tự tìm 3 bean có kiểu
`OrderRepository`, `OrderRelease`, `Clock` và truyền vào. Bạn **không bao giờ** viết `new OrderCancellation(...)` trong code app.

Vì sao dùng constructor (thay vì gắn `@Autowired` lên field)?
- Field là `final`: object tạo xong là đầy đủ, không đổi được.
- Viết test dễ: có thể tự `new` class với object giả nếu muốn (xem `WebhookSignatureVerifierTest`).

### 2.3. Các annotation tạo bean

| Annotation | Ý nghĩa | Ví dụ trong project |
|---|---|---|
| `@Component` | Bean chung chung | `OrderRelease`, `QrImageRenderer`, `OrderExpiryJob` |
| `@Service` | Bean chứa logic nghiệp vụ (giống `@Component`, chỉ khác tên gọi) | `OrderCreationService`, `CheckinService` |
| `@Repository` | Bean truy cập database; Spring đổi lỗi SQL thành exception dễ hiểu | `CatalogStore`, `TicketStore` |
| `@RestController` | Bean nhận request HTTP, trả JSON | `OrderController`, `TicketController` |
| `@Configuration` + `@Bean` | Class cấu hình; mỗi method `@Bean` trả về một bean | `ClockConfig`, `JwtConfig`, `KafkaConfig` |

Ví dụ `@Bean` trong `common/time/ClockConfig.java`:

```java
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
```

`Clock` là class của Java, bạn không thể gắn `@Component` lên nó. Nên ta viết một method trả về object, gắn `@Bean`,
và Spring đăng ký kết quả làm bean. Từ đó mọi class khai báo `Clock clock` trong constructor đều nhận được bean này.

Interface như `OrderRepository` cũng là bean, dù bạn không viết class nào cài đặt nó. Spring Data tự sinh class
cài đặt lúc chạy (xem mục 4.4).

### 2.4. Điểm khởi đầu: `CoreServiceApplication`

```java
@SpringBootApplication        // bật tự cấu hình + quét bean trong package com.ticket2h và các package con
@ConfigurationPropertiesScan  // tìm các record @ConfigurationProperties (mục 2.6)
@EnableScheduling             // cho phép @Scheduled (job chạy định kỳ)
public class CoreServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreServiceApplication.class, args);
    }
}
```

`SpringApplication.run` làm rất nhiều việc: đọc cấu hình, tạo mọi bean, chạy Flyway tạo bảng, mở cổng 8080 bằng
Tomcat nhúng, kết nối Kafka. Khi log in `Started CoreServiceApplication`, mọi thứ đã sẵn sàng.

Vì `@SpringBootApplication` nằm ở package `com.ticket2h`, mọi class trong `com.ticket2h.*` đều được quét.
Một class nằm ngoài package này sẽ **không** thành bean dù có `@Service`.

### 2.5. Auto-configuration và starter

Mở `app/pom.xml`, bạn thấy các dependency dạng `spring-boot-starter-xxx`:

| Starter | Spring Boot tự cấu hình |
|---|---|
| `spring-boot-starter-webmvc` | Tomcat, Spring MVC, chuyển object ↔ JSON (Jackson) |
| `spring-boot-starter-data-jpa` | Hibernate, `EntityManager`, các `JpaRepository` |
| `spring-boot-starter-jdbc` | `DataSource` (pool kết nối HikariCP), `JdbcTemplate` |
| `spring-boot-starter-flyway` | Chạy migration khi khởi động |
| `spring-boot-starter-security` | Bộ lọc bảo mật cho mọi request |
| `spring-boot-starter-kafka` | `KafkaTemplate` để gửi, `@KafkaListener` để nhận |

"Tự cấu hình" (auto-configuration) nghĩa là: thấy thư viện trên classpath + thấy cấu hình trong `application.yml`
thì Spring Boot tự tạo bean phù hợp. Ví dụ có `spring.datasource.url` → tự tạo `DataSource` nối tới Postgres.
Bạn chỉ viết cấu hình riêng khi cần khác mặc định (như `SecurityConfig`).

### 2.6. Cấu hình: `application.yml` và `@ConfigurationProperties`

`src/main/resources/application.yml` có hai loại cấu hình:

```yaml
spring:                       # cấu hình của Spring Boot (tên do Spring quy định)
  datasource:
    url: jdbc:postgresql://localhost:5432/core

ticket2h:                     # cấu hình riêng của ta
  order:
    hold-duration: PT10M      # 10 phút, viết theo chuẩn ISO-8601 của Duration
  payment:
    mockpay:
      api-key: ${MOCKPAY_API_KEY}   # lấy từ biến môi trường hoặc file .env
```

Phần `ticket2h.*` được "đổ" vào một record Java trong `common/config/Ticket2hProperties.java`:

```java
@Validated
@ConfigurationProperties("ticket2h")
public record Ticket2hProperties(Jobs jobs, OrderRules order, Payment payment, ...) {
    public record OrderRules(@NotNull Duration holdDuration, @NotNull Duration expiryGrace,
                             @NotNull Duration paymentExtension, @Positive int maxPaymentAttempts) {}
    ...
}
```

- Tên trong yml `hold-duration` tự khớp với tên Java `holdDuration`.
- `PT10M` tự chuyển thành `Duration`.
- `@Validated` + `@NotNull`: thiếu cấu hình thì app **không khởi động** và báo rõ thiếu gì. Tốt hơn chạy rồi lỗi giữa chừng.

Muốn dùng thì xin record này qua constructor: `properties.order().holdDuration()` (xem `OrderCreationService`).

`${MOCKPAY_API_KEY}` là chỗ giữ: Spring tìm giá trị trong biến môi trường, hoặc trong file `.env` dùng chung của hệ
thống ở repo infra (dòng `spring.config.import: optional:file:../infra/.env[.properties]`), hoặc `.env` của chính repo
(mẫu `.env.example`). Chạy bằng Docker thì `docker compose` truyền biến môi trường vào container. Secret không bao giờ nằm trong code hay git.

### 2.7. Profile: chạy cùng code với cấu hình khác nhau

Profile là tên một "chế độ chạy". Project có hai:

- **`dev`**: khi chạy với profile này, class `dev/DevDataSeeder.java` (gắn `@Profile("dev")`) mới thành bean. Nó tạo
  sẵn tài khoản, sự kiện, ghế. Ở môi trường thật không bật `dev` nên class này không tồn tại.
- **`test`**: test bật profile này, Spring đọc thêm `src/test/resources/application-test.yml`, ghi đè một số cấu
  hình (tắt job định kỳ, secret giả).

### 2.8. `@Transactional`: cái quan trọng nhất cần hiểu đúng

**Transaction** là một nhóm câu lệnh SQL "cùng sống cùng chết": hoặc tất cả được lưu (**commit**), hoặc không câu
nào được lưu (**rollback**). Ví dụ tạo đơn gồm: thêm đơn, trừ vé GA, giữ ghế. Nếu giữ ghế thất bại mà vé GA đã bị
trừ thì dữ liệu sai. Đặt cả ba trong một transaction thì lỗi ở đâu cũng rollback hết.

Trong Spring chỉ cần gắn annotation:

```java
@Transactional
public CreatedOrder create(UUID userId, String idempotencyKey, ...) {
    orders.saveAndFlush(order);           // 1
    purchaseQuotas.reserve(...);          // 2
    gaInventory.take(...);                // 3  ← ném SoldOutException
    seatHolds.hold(...);                  // 4  (không chạy tới)
}                                         // → Spring rollback: bước 1, 2 cũng bị huỷ
```

**Cách nó hoạt động (và cái bẫy).** Spring không sửa code của bạn. Nó tạo một object "bọc ngoài" (**proxy**) cho bean.
Khi class khác gọi `creation.create(...)`, thực ra gọi vào proxy; proxy mở transaction, gọi method thật, rồi commit
hoặc rollback. Hệ quả:

> Gọi một method `@Transactional` **từ chính class đó** (`this.create(...)`) thì **không** đi qua proxy,
> nên **không có transaction**. Lỗi này gọi là *self-invocation*.

Đây là lý do `OrderPlacement` và `OrderCreationService` là **hai class riêng** (mục 9.3). Khi viết project này,
chính `AccountService.register()` từng mắc lỗi đó: nó gọi `createUser()` cùng class mà quên `@Transactional` ở
`register()`. Đọc lại `identity/AccountService.java` để thấy cách sửa.

Ba quy tắc khác:

1. Spring chỉ rollback khi gặp **unchecked exception** (kế thừa `RuntimeException`). Vì vậy mọi lỗi nghiệp vụ trong
   project kế thừa `BusinessException extends RuntimeException`.
2. **Propagation** quy định method xử lý thế nào khi đã có (hoặc chưa có) transaction:

   | Kiểu | Nghĩa | Dùng ở |
   |---|---|---|
   | `REQUIRED` (mặc định) | Có sẵn thì tham gia, chưa có thì mở mới | Hầu hết các method |
   | `MANDATORY` | Bắt buộc phải có sẵn, không có thì ném lỗi | `SeatHoldService.hold`, `OutboxWriter.write`: chỉ được gọi bên trong transaction nghiệp vụ |
   | `REQUIRES_NEW` | Luôn mở transaction mới, độc lập | `WebhookLogWriter`: log webhook vẫn còn dù xử lý webhook bị rollback |

3. `@Transactional(readOnly = true)` cho method chỉ đọc: rõ ý định, và Hibernate bỏ qua bước kiểm tra thay đổi.

### 2.9. Job chạy định kỳ: `@Scheduled`

```java
@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")   // chỉ tạo bean khi cấu hình = true
class OrderJobsScheduler {
    ...
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)     // chạy xong thì 5 giây sau chạy lại
    void sweepExpiredOrders() {
        expiryJob.sweep();
    }
}
```

Mẫu trong project: logic nằm ở một bean luôn tồn tại (`OrderExpiryJob`), còn phần hẹn giờ nằm ở bean riêng có điều
kiện (`OrderJobsScheduler`). Trong test, `ticket2h.jobs.enabled=false` nên không có gì chạy ngầm; test tự gọi
`expiryJob.sweep()` đúng lúc nó muốn.

---

## 3. Cấu trúc project

### 3.1. Maven và `pom.xml`

**Maven** là công cụ build: tải thư viện (dependency), biên dịch, chạy test, đóng gói. Cấu hình nằm trong `pom.xml`.
`./mvnw` là *Maven wrapper*: tự tải đúng phiên bản Maven, nên máy không cần cài Maven.

Repo có một `pom.xml`, kế thừa `spring-boot-starter-parent` (Java 25, Spring Boot 4.1.1). Hồi còn monolith, project
có 3 `pom.xml` (một pom cha và hai module `app`, `mock-payment-gateway`); khi tách microservice, mỗi service thành
một repo với `pom.xml` riêng, build độc lập.

Một dependency trong `pom.xml`:

```xml
<dependency>
    <groupId>com.google.zxing</groupId>   <!-- tổ chức -->
    <artifactId>core</artifactId>         <!-- thư viện -->
    <version>${zxing.version}</version>   <!-- thư viện ngoài Spring phải ghi version -->
</dependency>
```

Dependency của Spring (`spring-boot-starter-...`) không ghi version: lấy từ `spring-boot-starter-parent`.

`<scope>test</scope>` nghĩa là thư viện chỉ dùng khi chạy test (JUnit, Testcontainers), không vào bản build thật.

Lệnh hay dùng:

| Lệnh | Làm gì |
|---|---|
| `./mvnw compile` | Biên dịch |
| `./mvnw test` | Chạy unit test (class tên `*Test`) |
| `./mvnw verify` | Chạy cả integration test (class tên `*IT`) |
| `./mvnw test -Dtest=RequestHashTest` | Chạy một class unit test |
| `./mvnw verify -Dit.test=SeatClaimConcurrencyIT` | Chạy một class integration test |

### 3.2. Package theo nghiệp vụ

Có hai cách chia package phổ biến:

- **Theo tầng (layer):** `controller/`, `service/`, `repository/`. Code của một chức năng rải ra nhiều nơi.
- **Theo nghiệp vụ (feature):** `ordering/`, `payment/`... Mọi thứ về đơn hàng nằm cùng chỗ. Project này dùng cách này.

```
com.ticket2h
├── common/        dùng chung: cấu hình, Clock, lỗi (ProblemDetail), bảo mật JWT, định nghĩa event Kafka
├── identity/      tài khoản, đăng ký, đăng nhập, ban tổ chức
├── catalog/       địa điểm, sự kiện, loại vé, sơ đồ ghế, tìm kiếm
├── inventory/     giữ ghế, tồn kho vé GA, hạn mức mỗi người
├── ordering/      đơn hàng, idempotency, hết hạn, huỷ
├── payment/       tạo thanh toán, webhook, hoàn tiền
├── outbox/        ghi event cùng transaction, đẩy lên Kafka, cấu hình Kafka
├── ticketing/     phát vé, QR, check-in, chuyển nhượng; API nội bộ cho notification-service
└── dev/           dữ liệu mẫu, endpoint chỉ có ở profile dev
```

### 3.3. Quy tắc giữa các module

Mỗi module tự quản dữ liệu của mình. **Module này không gọi repository của module khác**, mà gọi qua một service
công khai:

| Module | "Cửa" công khai cho module khác | Ví dụ người dùng |
|---|---|---|
| identity | `UserDirectory` | ticketing (tìm người nhận vé theo email) |
| catalog | `CatalogQueries` | ordering (lấy thông tin sự kiện, giá vé) |
| inventory | `SeatHoldService`, `GaInventory`, `PurchaseQuotas` | ordering, payment |
| ordering | `OrderService` | payment, ticketing (khoá đơn, lấy item) |
| ticketing | `TicketQueries` | `InternalTicketController` (API nội bộ cho notification-service) |

Java giúp giữ quy tắc này bằng **phạm vi truy cập**: nhiều class không có chữ `public` (ví dụ
`interface OrderRepository`), nên chỉ class trong cùng package mới thấy. Khi tách microservice, các "cửa" này chính là
chỗ cắt: notification trước đây gọi `UserDirectory` và `TicketQueries`, nay gọi HTTP tới `/internal/users/{id}` và
`/internal/tickets` của core (`InternalUserController`, `InternalTicketController`).

### 3.4. Quy ước đặt tên

| Hậu tố | Vai trò |
|---|---|
| `*Controller` | Nhận HTTP request |
| `*Service`, tên động từ (`OrderPlacement`, `PaymentInitiation`) | Logic nghiệp vụ |
| `*Repository` | Interface Spring Data JPA |
| `*Store` | Class tự viết SQL bằng JDBC |
| `*Job`, `*Sweeper`, `*Scheduler` | Việc chạy nền |
| `*Exception` | Lỗi nghiệp vụ |
| `*Test` | Unit test (không cần database) |
| `*IT` | Integration test (chạy Postgres, Kafka thật) |

---

## 4. Database: PostgreSQL, Flyway, JPA và JDBC

### 4.1. Flyway: lịch sử thay đổi của database

Bảng không được tạo bằng tay. Mỗi thay đổi cấu trúc là một file SQL trong
`src/main/resources/db/migration/`, đánh số tăng dần:

| File | Mốc | Nội dung |
|---|---|---|
| `V1__m1_identity.sql` | M1 | `organizers`, `users` |
| `V2__m1_catalog.sql` | M1 | `venues`, `events`, `ticket_types`, `seats` |
| `V100__m2_orders.sql` | M2 | `orders`, `order_items`, `purchase_quotas` |
| `V101__m2_inventory.sql` | M2 | Cột tồn kho GA, cột trạng thái ghế |
| `V102__m2_payments.sql` | M2 | `payments`, `payment_webhook_logs` |
| `V110__m3_orders_fulfilled.sql` | M3 | Trạng thái `FULFILLED` |
| `V111__m3_tickets.sql` | M3 | `event_staff`, `tickets`, `ticket_transfers`, `checkin_logs` |
| `V112__m3_outbox_notifications.sql` | M3 | `outbox_events`, `notification_deliveries` |
| `V113__notification_moved_out.sql` | Microservice | Xoá `notification_deliveries`: bảng này chuyển sang database của notification-service |

Khi app khởi động, Flyway xem bảng `flyway_schema_history` (mở trong DBeaver để thấy) để biết file nào đã chạy,
rồi chạy các file mới theo thứ tự. Quy tắc vàng: **không sửa file đã chạy**; muốn đổi gì thì thêm file mới. Ví dụ
`V113` xoá một bảng mà `V112` đã tạo, chứ không sửa `V112`.

### 4.2. Các bảng chính

```
organizers ─┬─< users (ORGANIZER thuộc một ban tổ chức)
            └─< venues ─< events ─┬─< ticket_types ─< seats
                                  └─< event_staff >── users (STAFF)

users ─< orders ─< order_items >── ticket_types / seats
           ├─< payments
           └─< tickets ─< ticket_transfers
                  └ owner_user_id ──> users

purchase_quotas (user_id, event_id, reserved)   ← hạn mức mỗi người mỗi sự kiện
outbox_events, payment_webhook_logs, checkin_logs   ← bảng kỹ thuật / log
```

`A ─< B` nghĩa là một A có nhiều B (khoá ngoại nằm ở B).

### 4.3. Ràng buộc: lớp chặn cuối cùng

Code có thể có bug. Ràng buộc (constraint) trong database là lưới an toàn: dữ liệu sai thì database **từ chối lưu**.
Ví dụ trong `V101__m2_inventory.sql`:

```sql
ALTER TABLE seats ADD CONSTRAINT seats_state_check CHECK (
    (status = 'AVAILABLE' AND order_id IS NULL     AND held_until IS NULL)
 OR (status = 'HELD'      AND order_id IS NOT NULL AND held_until IS NOT NULL)
 OR (status = 'SOLD'      AND order_id IS NOT NULL AND held_until IS NULL)
);
```

Ghế `HELD` mà không có `order_id` là vô lý; nếu code lỡ ghi như vậy, database báo lỗi ngay. Thử trong DBeaver:
`UPDATE seats SET status = 'HELD' WHERE id = (SELECT id FROM seats LIMIT 1);` sẽ bị từ chối.

Các loại ràng buộc trong project:
- `CHECK`: điều kiện trên một dòng (`available_quantity` từ 0 đến `total_quantity`).
- `UNIQUE`: không trùng (`uq_orders_user_idempotency_key`: mỗi người một key chỉ có một đơn).
- **Partial unique index**: không trùng **trong một tập con** (`uq_tickets_active_seat`: một ghế chỉ có tối đa
  một vé chưa bị huỷ).
- `FOREIGN KEY`: tham chiếu phải tồn tại.

Mọi ràng buộc đều có **tên** (`seats_state_check`...). Code dựa vào tên này để nhận diện lỗi (xem mục 9.3).
Danh sách đầy đủ: `docs/schema.md`.

### 4.4. JPA/Hibernate: làm việc với bảng như với object

**JPA** là chuẩn của Java để ánh xạ bảng ↔ class (ORM). **Hibernate** là thư viện cài đặt chuẩn đó.
Một **entity** là class tương ứng một bảng. Xem `ordering/Order.java` (rút gọn):

```java
@Entity(name = "PurchaseOrder")   // "Order" là từ khoá trong ngôn ngữ truy vấn JPQL nên đặt tên khác
@Table(name = "orders")           // bảng thật tên "orders"
public class Order extends AssignedIdEntity {

    private UUID userId;          // cột user_id (Spring tự đổi camelCase → snake_case)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;   // lưu enum dưới dạng chữ: 'PENDING_PAYMENT'
    private BigDecimal totalAmount;
    private Instant expiresAt;
    ...
    protected Order() {}          // JPA cần constructor không tham số

    public void markPaid(Instant now) {   // đổi trạng thái qua method có kiểm tra, KHÔNG có setStatus()
        transition(OrderStatus.PAID, now);
        this.paidAt = now;
    }
}
```

Truy vấn bằng **Spring Data repository**: bạn chỉ khai báo interface, Spring tự sinh code. Xem
`ordering/OrderRepository.java`:

```java
interface OrderRepository extends JpaRepository<Order, UUID> {

    // Spring đọc TÊN method rồi tự sinh câu truy vấn: WHERE user_id = ? AND idempotency_key = ?
    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // Câu SQL tự viết, dùng khi cần tính năng riêng của PostgreSQL như FOR UPDATE
    @Query(value = "SELECT * FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Order> lockById(@Param("id") UUID id);
}
```

`JpaRepository` cho sẵn `save`, `saveAndFlush`, `findById`, `findAll`, `delete`...

**Dirty checking:** bên trong một transaction, khi bạn gọi `order.markPaid(now)` trên entity Hibernate đang quản lý,
bạn **không cần gọi save**. Lúc commit, Hibernate tự so sánh và sinh câu `UPDATE orders ...`.

**`AssignedIdEntity`** (`common/persistence/`): id của đơn là UUID do ta tự tạo. Mặc định Spring Data thấy id
khác null thì tưởng là dòng cũ, chạy `SELECT` trước rồi mới `INSERT`. Class này báo cho Spring biết "đây là dòng
mới", để `saveAndFlush` chạy `INSERT` ngay. Chi tiết này quan trọng cho idempotency (mục 9.3).

### 4.5. JDBC: tự viết SQL

Nhiều chỗ trong project **không** dùng entity mà viết SQL trực tiếp bằng `NamedParameterJdbcTemplate`. Xem
`inventory/GaInventory.java`:

```java
int updated = jdbc.update("""
        UPDATE ticket_types
        SET available_quantity = available_quantity - :quantity
        WHERE id = :ticketTypeId
          AND event_id = :eventId
          AND kind = 'GA'
          AND available_quantity >= :quantity
        """, Map.of("quantity", quantity, "ticketTypeId", ticketTypeId, "eventId", eventId));
if (updated == 0) {
    throw new SoldOutException(ticketTypeId);
}
```

- `"""..."""` là **text block** của Java (chuỗi nhiều dòng).
- `:quantity` là **tham số có tên**, giá trị lấy từ `Map`. Không bao giờ nối chuỗi giá trị vào SQL
  (`"... = " + x`): như vậy là mở cửa cho SQL injection.
- `update(...)` trả về **số dòng bị ảnh hưởng**. Đây là chìa khoá của nhiều kỹ thuật ở mục 8.

Đọc dữ liệu thì dùng `query` với một lambda biến mỗi dòng (`ResultSet`) thành object:

```java
List<UUID> ids = jdbc.queryForList("SELECT id FROM tickets WHERE order_id = :orderId", Map.of(...), UUID.class);
```

### 4.6. Khi nào dùng JPA, khi nào dùng SQL

| Bảng | Cách | Lý do |
|---|---|---|
| `orders`, `payments`, `users` | JPA entity | Nhiều trường, đổi trạng thái qua method có kiểm tra |
| `seats`, `ticket_types` (số vé GA), `purchase_quotas`, `tickets` | SQL có điều kiện | Nhiều người tranh cùng dòng; một câu `UPDATE ... WHERE` vừa kiểm tra vừa ghi |
| `events`, `venues` | SQL (`catalog/CatalogStore.java`) | Truy vấn đơn giản, tránh entity ghi đè số vé GA |

Quy tắc an toàn: **không trộn** entity và SQL thuần trên **cùng một dòng** trong một transaction. Entity trong bộ
nhớ giữ giá trị cũ, lúc commit có thể ghi đè giá trị mà SQL vừa đổi.

### 4.7. Thời gian: `Clock`, `Instant`, `timestamptz`

- Mọi thời điểm là `Instant` (một điểm trên trục thời gian, theo UTC). Cột database là `timestamptz`.
- Không bao giờ gọi `Instant.now()` trong code nghiệp vụ. Luôn dùng `clock.instant()` với `Clock` được inject.
  Lý do: trong test, `Clock` được thay bằng `MutableClock` để "tua" thời gian (`clock.advance(Duration.ofMinutes(12))`)
  mà không phải chờ thật 12 phút.
- Không dùng `now()` của PostgreSQL trong logic, vì nó không theo đồng hồ của test.
- `Times.utc(instant)` đổi `Instant` thành `OffsetDateTime` để driver PostgreSQL ghi đúng.

---

## 5. Tầng web: controller, DTO, validation, xử lý lỗi

### 5.1. Một request đi qua những đâu

```
HTTP request
   │
   ▼
Bộ lọc Spring Security ──── sai/thiếu JWT ──► 401
   │ (đã biết là ai, vai trò gì)
   ▼
DispatcherServlet: tìm method controller khớp URL + HTTP method
   │
   ▼
Controller: đọc JSON → record DTO, kiểm tra @Valid ──── sai ──► 400
   │
   ▼
Service (@Transactional): logic nghiệp vụ, gọi repository/SQL ──── BusinessException ──► 4xx
   │
   ▼
Controller: đổi kết quả thành DTO → JSON → HTTP response
```

### 5.2. Controller

Xem `ordering/OrderController.java` (rút gọn):

```java
@RestController                       // bean xử lý HTTP; giá trị trả về tự thành JSON
class OrderController {

    @PostMapping("/orders")           // POST /orders
    ResponseEntity<OrderResponse> create(
            AuthUser caller,                                             // người đang đăng nhập (mục 6.4)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {            // body JSON → record, có kiểm tra
        ...
        PlacementResult result = placement.place(caller.id(), idempotencyKey, request);
        OrderResponse body = OrderResponse.of(result.order(), result.items());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);   // 200
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);                       // 201
    }

    @GetMapping("/orders/{orderId}")  // {orderId} lấy từ URL
    OrderResponse get(AuthUser caller, @PathVariable UUID orderId) { ... }
}
```

| Annotation | Lấy dữ liệu từ đâu | Ví dụ |
|---|---|---|
| `@PathVariable` | Một đoạn của URL | `/orders/{orderId}` |
| `@RequestParam` | Tham số sau dấu `?` | `/events?q=son tung&page=0` |
| `@RequestHeader` | Header HTTP | `Idempotency-Key` |
| `@RequestBody` | Body JSON | `CreateOrderRequest` |

Controller nên **mỏng**: nhận input, gọi service, đổi kết quả thành response. Không chứa logic nghiệp vụ.

### 5.3. DTO bằng `record`

**DTO** (Data Transfer Object) là object chỉ để chở dữ liệu vào/ra API. Java `record` rất hợp: ngắn gọn,
không đổi được. Thư viện **Jackson** tự chuyển JSON ↔ record theo tên trường.

```java
record OrderResponse(UUID orderId, OrderStatus status, BigDecimal totalAmount, ...) {
    static OrderResponse of(Order order, List<OrderItem> items) {   // đổi entity → DTO
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(), ...);
    }
}
```

Vì sao không trả thẳng entity ra API? Entity có thể chứa trường không nên lộ (như `requestHash`, mật khẩu đã băm
trong `User`), và đổi cấu trúc bảng sẽ làm đổi API ngoài ý muốn.

### 5.4. Validation

`ordering/CreateOrderRequest.java`:

```java
public record CreateOrderRequest(
        @NotNull UUID eventId,
        @NotEmpty @Size(max = 10) List<@Valid @NotNull Item> items) {

    public record Item(
            @NotNull UUID ticketTypeId,
            @Size(max = 10) List<@NotNull UUID> seatIds,
            @Positive @Max(20) Integer quantity) { ... }
}
```

Khi controller có `@Valid @RequestBody`, Spring kiểm tra các annotation này trước khi gọi method. Sai thì trả 400.
Validation bằng annotation chỉ kiểm tra **hình dạng** dữ liệu. Kiểm tra cần database (loại vé có thuộc sự kiện
không, ghế có đúng loại vé không) nằm trong service (`ordering/OrderPricing.java`).

Lưu ý: record không có trường `price`. Client gửi `"price": 1` thì Jackson **bỏ qua**, giá luôn lấy từ database.
Nhận giá từ client là lỗi bảo mật kinh điển.

### 5.5. Xử lý lỗi thống nhất

Mọi lỗi API trả cùng một dạng JSON theo chuẩn RFC 9457 (*Problem Details*):

```json
{
  "type": "https://ticket2h.dev/errors/seat-unavailable",
  "title": "Ghế không còn trống",
  "status": 409,
  "detail": "Có ghế đã được người khác giữ",
  "code": "SEAT_UNAVAILABLE",
  "unavailableSeatIds": ["5d2e..."]
}
```

Ba mảnh ghép trong `common/error/`:

1. **`ErrorCode`**: enum liệt kê mọi mã lỗi kèm HTTP status và tiêu đề tiếng Việt
   (`SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "Ghế không còn trống")`).
2. **`BusinessException`**: lớp cha của mọi lỗi nghiệp vụ, mang một `ErrorCode`. Các lớp con:
   `SeatUnavailableException`, `SoldOutException`, `NotFoundException`...
   ```java
   public class SoldOutException extends BusinessException {
       public SoldOutException(UUID ticketTypeId) {
           super(ErrorCode.SOLD_OUT, "Loại vé này không còn đủ số lượng");
           with("ticketTypeId", ticketTypeId);   // trường bổ sung trong JSON
       }
   }
   ```
3. **`ApiExceptionHandler`** (`@RestControllerAdvice`): bắt exception ném ra từ **mọi** controller và đổi thành JSON:
   ```java
   @ExceptionHandler(BusinessException.class)
   ResponseEntity<ProblemDetail> handleBusiness(BusinessException e) {
       return ResponseEntity.status(e.code().status()).body(ProblemDetails.of(e));
   }
   ```

Nhờ vậy service chỉ cần `throw new SoldOutException(id)`, không phải lo HTTP. Danh sách mã lỗi: `docs/api.md`.

Lỗi không lường trước (bug, mất kết nối) rơi vào `handleUnexpected`: ghi log đầy đủ, trả 500 với thông báo chung
chung. Không bao giờ trả stack trace ra ngoài.

---

## 6. Bảo mật: đăng nhập JWT và phân quyền

### 6.1. Xác thực và phân quyền

- **Xác thực (authentication):** bạn là ai? → đăng nhập, nhận token.
- **Phân quyền (authorization):** bạn được làm gì? → kiểm tra vai trò, quyền sở hữu.

> Từ bước 2 microservice, **đăng ký, đăng nhập, phát token nằm ở identity-service** (repo [identity-service](https://github.com/ticket2h/identity-service),
> cổng 8082); core-service chỉ kiểm tra token. Mục 6.2 và phần "phát token" của 6.3 nói về code bên identity.

### 6.2. Mật khẩu

`service/AccountService.java` của identity-service không bao giờ lưu mật khẩu gốc. Nó lưu **bản băm** bằng BCrypt
(`passwordEncoder.encode(password)`), kết quả dạng `{bcrypt}$2a$10$...`. Khi đăng nhập, so khớp bằng
`passwordEncoder.matches(raw, hash)`. Băm là một chiều: có bản băm cũng không suy ra được mật khẩu.

Chi tiết nhỏ mà hay: khi email không tồn tại, code vẫn chạy `matches` với một bản băm giả. Nếu không, đăng nhập
email sai trả lời nhanh hơn hẳn mật khẩu sai, và kẻ xấu đo thời gian là biết email nào đã đăng ký.

### 6.3. JWT

Đăng nhập thành công, `POST /auth/login` (identity-service) trả về một **access token** dạng JWT (JSON Web Token):

```
eyJraWQiOiJrMSIsImFsZyI6IlJTMjU2In0 . eyJpc3MiOiJ0aWNrZXQyaCIsInN1YiI6... . Qm9x...
              header                             payload (claims)            chữ ký
```

Header và payload (giải mã base64) là JSON công khai, ai cũng đọc được:

```json
{ "kid": "k1", "alg": "RS256" }
{ "iss": "ticket2h", "sub": "<userId>", "role": "ORGANIZER", "organizerId": "<id ban tổ chức>",
  "iat": 1793534400, "exp": 1793538000 }
```

**Chữ ký** RS256 dùng một **cặp khoá**: identity ký bằng **private key** (`JWT_PRIVATE_KEY`, chỉ identity có), ai có
**public key** cũng kiểm tra được nhưng không ký được. Sửa một ký tự của payload (ví dụ đổi `CUSTOMER` thành
`ADMIN`) thì chữ ký không còn khớp, server từ chối. Nhờ vậy server không cần lưu phiên đăng nhập (**stateless**):
mỗi request mang token, server kiểm tra chữ ký là đủ.

Vì sao không dùng HS256 (một secret) như hồi monolith? Với HS256, ai kiểm tra được token cũng **phát** được token,
nên core sẽ phải giữ secret của identity, và lộ secret ở core là lộ quyền tạo tài khoản ADMIN giả. Với RS256, core
chỉ cần public key (công khai), lấy từ `GET http://localhost:8082/.well-known/jwks.json` (chuẩn **JWKS**). `kid` trong
header cho biết dùng khoá nào, nên sau này identity đổi khoá mà core không phải sửa gì.

- Phát token: `service/TokenService.java` của identity-service.
- Kiểm tra token ở core: `common/security/JwtConfig.java` (`NimbusJwtDecoder.withJwkSetUri(...)`, chỉ nhận RS256).
  Hạn token được kiểm tra theo **cùng `Clock`** với phần còn lại của app.
- identity không trả lời lúc core cần tải JWKS: `TokenVerificationUnavailableFilter` trả 502 thay vì 500.

Client gửi token trong header mỗi request: `Authorization: Bearer eyJhbGci...`.

### 6.4. `SecurityConfig`: ai được gọi gì

`common/security/SecurityConfig.java` khai báo luật theo đường dẫn:

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()   // /webhooks/...
        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()     // /events, /events/*/seats...
        .requestMatchers("/organizer/**").hasRole("ORGANIZER")
        .requestMatchers(HttpMethod.POST, "/orders").hasRole("CUSTOMER")
        .requestMatchers("/checkin").hasRole("STAFF")
        .anyRequest().authenticated())                               // còn lại: chỉ cần đăng nhập
```

- Không có token hoặc token sai → **401** `UNAUTHENTICATED`.
- Có token nhưng sai vai trò (khách gọi `/checkin`) → **403** `FORBIDDEN`.
- CSRF bị tắt vì API không dùng cookie đăng nhập (CSRF chỉ nguy hiểm khi trình duyệt tự gửi cookie).

### 6.5. Controller biết "ai đang gọi" thế nào

Khai báo tham số kiểu `AuthUser` là đủ:

```java
@GetMapping("/me/tickets")
List<TicketResponse> mine(AuthUser caller) {      // caller.id(), caller.role()
    return queries.ownedBy(caller.id()) ...
}
```

Phép màu nằm ở `common/security/AuthUserArgumentResolver.java`: mỗi lần controller cần một `AuthUser`, Spring gọi
class này; nó lấy JWT đã được kiểm tra từ `SecurityContextHolder` và tạo `AuthUser(userId, role, organizerId)`.
`organizerId` lấy từ claim cùng tên: core biết người gọi thuộc ban tổ chức nào mà không phải hỏi identity.

### 6.6. 404 hay 403?

Hai kiểu kiểm tra quyền khác nhau:

- **Theo vai trò** (khách gọi API của BTC) → 403. Ở `SecurityConfig`.
- **Theo quyền sở hữu** (khách A xem đơn của khách B) → **404**, như thể đơn không tồn tại. Nếu trả 403, kẻ xấu
  biết được "đơn này có thật", rồi dò id. Xem `orders.findForUser(orderId, caller.id())`: truy vấn có điều kiện
  `user_id`, đơn của người khác đơn giản là không tìm thấy.
- Riêng BTC sửa sự kiện của BTC khác trả **403** theo yêu cầu của lộ trình M1 (sự kiện đã công bố là công khai, nên
  không có gì để giấu).

---

## 7. M1: tài khoản, sự kiện, tìm kiếm không dấu

### 7.1. identity: tài khoản (nay ở identity-service)

Code ở repo [identity-service](https://github.com/ticket2h/identity-service), package `com.ticket2h.identity` chia theo tầng (`controller/`, `service/`,
`repository/`, `entity/`, `dto/`). Trong core, package `identity` chỉ còn `UserDirectory` và `IdentityClient` để tra
tài khoản theo email qua API nội bộ của identity (chuyển nhượng vé, phân công soát vé).

| Việc | Endpoint (identity, cổng 8082) | Code |
|---|---|---|
| Khách tự đăng ký (luôn là CUSTOMER) | `POST /auth/register` | `AuthController.register` → `AccountService.register` |
| Đăng nhập | `POST /auth/login` | `AccountService.login` → `TokenService.issue` |
| Xem thông tin mình | `GET /me` | `AuthController.me` |
| Admin tạo ban tổ chức | `POST /admin/organizers` | `AdminController` |
| Admin tạo tài khoản STAFF/ORGANIZER/ADMIN | `POST /admin/users` | `AccountService.createUser` |

Điểm đáng học trong `AccountService.createUser`:
- Email được **chuẩn hoá** (`Emails.normalize`: bỏ khoảng trắng, chữ thường) trước khi lưu và trước khi tìm.
  Database còn có ràng buộc `users_email_lowercase` để chắc chắn.
- Kiểm tra trùng email bằng `existsByEmail`, **nhưng** hai người đăng ký cùng email cùng lúc vẫn có thể cùng qua
  bước kiểm tra. Lớp chặn thật là ràng buộc `uq_users_email`; code bắt `DataIntegrityViolationException` và đổi
  thành lỗi 409 dễ hiểu. Đây là bài học sẽ gặp lại ở mục 8: **kiểm tra trong Java không đủ khi có nhiều request song song.**

### 7.2. catalog: ban tổ chức quản lý sự kiện

Vòng đời một sự kiện:

```
DRAFT ──(thêm loại vé, thêm ghế, sửa thông tin)──► publish ──► PUBLISHED (khách thấy và mua được)
```

`catalog/OrganizerCatalogService.java` có các quy tắc:
- Mọi thao tác trên sự kiện đều qua `requireOwner(organizerId, event)`: sự kiện của BTC khác → `ForbiddenException` (403).
  Đây chính là tiêu chí M1 "BTC A sửa sự kiện của BTC B thì bị 403".
- Chỉ thêm loại vé, ghế khi sự kiện còn `DRAFT`. Đã bán vé rồi mà đổi cấu trúc vé thì rất dễ sinh dữ liệu sai.
- Công bố (`publish`) chỉ khi có ít nhất một loại vé, và loại vé có ghế (`SEATED`) thì phải có ghế.
- Thêm ghế theo hàng: `{section: "VIP", rows: [{label: "A", from: 1, to: 20}]}` sinh 20 ghế A-1 … A-20 bằng một lần
  `batchUpdate` (gửi nhiều câu INSERT trong một lần, nhanh hơn nhiều so với từng câu).

Hai loại vé:

| `kind` | Ý nghĩa | Lưu số lượng ở đâu |
|---|---|---|
| `SEATED` | Ghế có số, mỗi vé một ghế | Mỗi ghế một dòng trong `seats` |
| `GA` | Vé đứng (General Admission), chỉ có số lượng | `ticket_types.total_quantity`, `available_quantity` |

Ràng buộc `fk_seats_ticket_type_same_event` (khoá ngoại hai cột `(ticket_type_id, event_id)`) bảo đảm ghế chỉ thuộc
loại vé **của chính sự kiện đó**: một mẹo nhỏ để database tự chặn dữ liệu chéo sự kiện.

### 7.3. Tìm kiếm tiếng Việt không dấu

Yêu cầu: gõ "son tung" ra "Sơn Tùng M-TP Live Concert". Cách làm trong `catalog/VietnameseText.java`:

```java
public static String normalize(String text) {
    String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
    String withoutMarks = COMBINING_MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    return NON_ALPHANUMERIC.matcher(withoutMarks).replaceAll(" ").trim();
}
```

Từng bước với `"Sơn Tùng M-TP"`:

| Bước | Kết quả | Giải thích |
|---|---|---|
| `toLowerCase` + đổi `đ` | `sơn tùng m-tp` | `đ` là một chữ riêng, không phải "d + dấu", nên phải đổi tay |
| `Normalizer.NFD` | `s` `o` `̛` `n` … `u` `̀` `n` `g` … | Tách mỗi chữ có dấu thành chữ gốc + ký tự dấu đi kèm |
| Xoá ký tự dấu (`\p{M}`) | `son tung m-tp` | `\p{M}` là mọi "dấu kết hợp" trong Unicode |
| Đổi ký tự lạ thành khoảng trắng | `son tung m tp` | Chỉ giữ `a-z`, `0-9` |

Khi tạo hoặc sửa sự kiện, app lưu `normalize(tên + tên địa điểm + thành phố)` vào cột `events.search_text`.
Khi tìm, câu hỏi cũng đi qua `normalize`, tách thành từng từ, và mỗi từ phải xuất hiện:
`search_text LIKE '%son%' AND search_text LIKE '%tung%'`. Index `pg_trgm` (trigram) giúp `LIKE '%...%'` vẫn nhanh
khi có nhiều sự kiện. Vì kết quả `normalize` chỉ chứa `a-z0-9`, ký tự đặc biệt của `LIKE` như `%` không lọt vào được.

---

## 8. Xử lý đồng thời: phần quan trọng nhất

Đây là lý do Ticket2h tồn tại. Đọc chậm phần này.

### 8.1. Race condition: lỗi chỉ xuất hiện khi đông người

Thử viết giữ ghế theo cách tự nhiên nhất:

```java
// SAI
String status = jdbc.queryForObject("SELECT status FROM seats WHERE id = :id", ...);
if (!"AVAILABLE".equals(status)) {
    throw new SeatUnavailableException(...);
}
jdbc.update("UPDATE seats SET status = 'HELD', order_id = :orderId ... WHERE id = :id", ...);
```

Một người chạy thì đúng. Hai người chạy **cùng lúc** trên hai luồng:

| Thời điểm | Người A | Người B |
|---|---|---|
| t1 | đọc: ghế `AVAILABLE` | |
| t2 | | đọc: ghế `AVAILABLE` |
| t3 | ghi: `HELD`, của A | |
| t4 | | ghi: `HELD`, của B ← **ghi đè A** |

Cả hai được báo "giữ ghế thành công". Đây là **race condition**, mẫu điển hình gọi là **check-then-act**
(kiểm tra rồi mới làm): giữa lúc kiểm tra và lúc làm, thế giới đã thay đổi.

Khi viết project, phiên bản sai này đã được chạy thật với 200 người cùng giữ một ghế: **khoảng 20 người** cùng
"thành công" mỗi lần (số liệu trong `docs/adr/002-giu-ghe-co-so.md`). Con số 20 bằng đúng số kết nối database cùng
lúc: mọi transaction đang chạy song song đều đọc thấy ghế trống.

Trong project có **ba kỹ thuật** chống race condition, mỗi kỹ thuật hợp với một tình huống.

### 8.2. Kỹ thuật 1: khoá dòng rồi mới kiểm tra (`SELECT ... FOR UPDATE`)

Dùng ở `inventory/SeatHoldService.hold` (giữ nhiều ghế có số):

```java
List<LockedSeat> locked = jdbc.query("""
        SELECT id, status, held_until
        FROM seats
        WHERE id IN (:seatIds) AND event_id = :eventId
        ORDER BY id
        FOR UPDATE
        """, ...);
// kiểm tra từng ghế trong lúc đang giữ khoá
List<UUID> unavailable = locked.stream().filter(seat -> !seat.claimableAt(now)).map(LockedSeat::id).toList();
if (!unavailable.isEmpty()) {
    throw new SeatUnavailableException(unavailable);
}
jdbc.update("UPDATE seats SET status = 'HELD', order_id = :orderId, held_until = :heldUntil WHERE id IN (:seatIds) AND ...");
```

`FOR UPDATE` **khoá** các dòng được đọc cho tới khi transaction kết thúc. Transaction khác muốn khoá cùng dòng phải
**đứng chờ**:

| Thời điểm | Người A | Người B |
|---|---|---|
| t1 | `SELECT ... FOR UPDATE` → khoá ghế, thấy `AVAILABLE` | |
| t2 | | `SELECT ... FOR UPDATE` → **chờ** |
| t3 | `UPDATE` → `HELD`; commit, nhả khoá | |
| t4 | | được chạy tiếp, đọc **bản mới nhất**: `HELD` → `SeatUnavailableException` |

Hợp với ghế có số vì ta cần biết chính xác **ghế nào** bận để báo cho khách (`unavailableSeatIds`).

### 8.3. Deadlock và vì sao luôn `ORDER BY id`

Hai người cùng giữ hai ghế, nhưng chọn theo thứ tự ngược nhau:

| Thời điểm | Người A muốn {X, Y} | Người B muốn {Y, X} |
|---|---|---|
| t1 | khoá X | khoá Y |
| t2 | chờ Y (B đang giữ) | chờ X (A đang giữ) |

Cả hai chờ nhau mãi: **deadlock**. PostgreSQL phát hiện và huỷ một bên, bên đó nhận lỗi 500.

Cách chữa: **mọi chỗ khoá nhiều dòng đều khoá theo cùng một thứ tự**. `ORDER BY id` bảo đảm cả A và B đều khoá X
trước (nếu X có id nhỏ hơn), nên bên đến sau chỉ chờ ở X, không bao giờ cầm một ghế rồi chờ ghế kia.

Quy tắc mở rộng cho cả hệ thống (trong `CLAUDE.md`), mọi luồng khoá theo thứ tự bảng:

```
orders → payments → purchase_quotas → ticket_types (theo id) → seats (theo id) → tickets
```

**Chuyện có thật trong project:** một agent review độc lập phát hiện hàm trả ghế `SeatHoldService.release()`
ban đầu chỉ là `UPDATE seats ... WHERE order_id = ?`. Câu UPDATE này khoá ghế theo thứ tự PostgreSQL quét bảng,
**không** theo id. Test `releasing_expired_seats_while_someone_retakes_them_never_deadlocks` được viết để tái hiện,
và nó đỏ ngay vòng đầu với `ERROR: deadlock detected`. Cách sửa là khoá theo id trước:

```java
public int release(UUID orderId) {
    lockHeldSeats(orderId);   // SELECT id FROM seats WHERE order_id = ? ... ORDER BY id FOR UPDATE
    return jdbc.update("UPDATE seats SET status = 'AVAILABLE', order_id = NULL, held_until = NULL WHERE ...");
}
```

Bài học: deadlock không hiện ra khi bạn tự test bằng tay. Chỉ test chạy song song nhiều lần mới bắt được.

### 8.4. Kỹ thuật 2: một câu `UPDATE` có điều kiện

Dùng ở `inventory/GaInventory.take` (vé GA, đã trích ở mục 4.5):

```sql
UPDATE ticket_types
SET available_quantity = available_quantity - :quantity
WHERE id = :ticketTypeId AND available_quantity >= :quantity
```

Kiểm tra (`available_quantity >= :quantity`) và ghi nằm trong **cùng một câu lệnh**, nên không có khe hở giữa
"kiểm tra" và "làm". Còn 1 vé, hai người cùng mua:

1. A chạy UPDATE: khoá dòng, trừ còn 0.
2. B chạy UPDATE cùng dòng: phải chờ A.
3. A commit. PostgreSQL **đánh giá lại điều kiện WHERE của B trên dữ liệu mới nhất**: `0 >= 1` sai.
4. Câu UPDATE của B ảnh hưởng **0 dòng** → code ném `SoldOutException`.

Không cần `SELECT ... FOR UPDATE` riêng. Ràng buộc `ticket_types_quantity_check` (không cho số vé âm) là lưới an
toàn nếu ai đó viết sai câu lệnh.

Hợp với **bộ đếm**: chỉ cần biết "còn đủ hay không", không cần báo chi tiết. Đánh đổi: mọi người mua GA của một
loại vé xếp hàng trên **một dòng**. Khi cực đông, đây là điểm nghẽn (ghi ở ADR-003, để tối ưu ở M5).

### 8.5. Kỹ thuật 3: `INSERT ... ON CONFLICT DO UPDATE ... WHERE`

Dùng ở `inventory/PurchaseQuotas.reserve` (mỗi người tối đa 4 vé mỗi sự kiện):

```sql
INSERT INTO purchase_quotas (user_id, event_id, reserved)
VALUES (:userId, :eventId, :quantity)
ON CONFLICT (user_id, event_id)
DO UPDATE SET reserved = purchase_quotas.reserved + EXCLUDED.reserved
WHERE purchase_quotas.reserved + EXCLUDED.reserved <= :limit
```

- Chưa có dòng của người này → INSERT dòng mới.
- Đã có dòng → cộng thêm, **chỉ khi** không vượt hạn mức. Vượt thì câu lệnh ảnh hưởng 0 dòng → `PurchaseLimitExceededException`.
- `EXCLUDED` là dòng bạn **định** insert (tức `:quantity`).

Cách sai mà ai cũng nghĩ ra đầu tiên: `SELECT sum(quantity)` các đơn của người này, cộng thêm, so với 4. Hai tab
trình duyệt cùng đọc thấy "đã có 2", cùng cho mua thêm 2 → tổng 6.

Một chi tiết tinh tế: nhánh INSERT không đi qua điều kiện `WHERE` (chỉ nhánh UPDATE có). Nên code Java phải chặn
`quantity > limit` **trước** khi chạy câu lệnh.

### 8.6. Hết hạn lười (lazy expiry)

Ghế được giữ 10 phút (`held_until`). Hết 10 phút mà khách chưa trả tiền thì sao? Không cần chờ job dọn dẹp:
ghế `HELD` có `held_until` đã qua **được coi như còn trống**. Xem `LockedSeat.claimableAt`:

```java
boolean claimableAt(Instant now) {
    return "AVAILABLE".equals(status)
            || ("HELD".equals(status) && heldUntil.toInstant().isBefore(now));
}
```

Sơ đồ ghế công khai (`SeatMapController`) cũng hiển thị ghế quá hạn là `AVAILABLE`. Job dọn đơn (mục 9.5) chạy sau
để trả lại vé GA và hạn mức, nhưng người khác không phải đợi job mới mua được ghế.

### 8.7. Tóm tắt ba kỹ thuật

| Tình huống | Kỹ thuật | File |
|---|---|---|
| Nhiều dòng, cần biết dòng nào thất bại | Khoá theo id rồi kiểm tra (`ORDER BY id FOR UPDATE`) | `SeatHoldService` |
| Một bộ đếm, chỉ cần đủ/không đủ | `UPDATE ... WHERE còn đủ`, đếm số dòng ảnh hưởng | `GaInventory` |
| Bộ đếm có thể chưa tồn tại | `INSERT ... ON CONFLICT DO UPDATE ... WHERE` | `PurchaseQuotas` |
| Đổi trạng thái một vé (check-in, chuyển nhượng) | `UPDATE ... WHERE status = 'VALID' AND token_version = ?` | `TicketStore` |
| Đổi trạng thái một entity JPA | Khoá `FOR UPDATE` rồi gọi method của entity | `OrderRepository.lockById` |

Câu cần thuộc: **không bao giờ đọc lên, kiểm tra trong Java, rồi ghi lại mà không khoá**.

---

## 9. M2: tạo đơn, idempotency, hết hạn, huỷ

### 9.1. Vòng đời đơn hàng

```
                  thanh toán thành công        phát vé xong
PENDING_PAYMENT ─────────────────────► PAID ─────────────► FULFILLED
      │
      ├── job dọn đơn quá hạn ──► EXPIRED
      └── khách tự huỷ ─────────► CANCELLED
```

`PENDING_PAYMENT` **chính là** việc giữ chỗ: lúc tạo đơn, ghế và vé GA đã bị giữ cho khách trong 10 phút
(lý do ở ADR-001). Bước nào được đi, bước nào không, nằm trong `ordering/OrderStatus.java`:

```java
private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        PENDING_PAYMENT, EnumSet.of(PAID, EXPIRED, CANCELLED),
        PAID, EnumSet.of(FULFILLED),
        FULFILLED, EnumSet.noneOf(OrderStatus.class),
        EXPIRED, EnumSet.noneOf(OrderStatus.class),
        CANCELLED, EnumSet.noneOf(OrderStatus.class));
```

`Order` không có `setStatus()`. Muốn đổi trạng thái phải gọi `markPaid`, `markExpired`, `cancel`, `markFulfilled`.
Mỗi method kiểm tra `canTransitionTo`, sai thì ném `IllegalOrderTransitionException`. Nhờ vậy không ai lỡ tay
chuyển đơn đã huỷ sang đã thanh toán. `OrderStatusTest` kiểm tra đủ 5 × 5 = 25 cặp trạng thái.

### 9.2. Luồng tạo đơn

`OrderCreationService.create` chạy trong **một transaction**:

```
1. now = clock.instant()
2. Sự kiện có tồn tại, đã PUBLISHED, đang trong giờ bán?           → SALE_NOT_OPEN
3. Kiểm tra và tính giá (OrderPricing), chỉ đọc:
     - loại vé thuộc sự kiện, không lặp
     - SEATED: có seatIds, ghế đúng loại vé; GA: có quantity
     - giá lấy từ ticket_types.price, KHÔNG từ client
     - tổng số vé ≤ hạn mức mỗi người                              → PURCHASE_LIMIT_EXCEEDED
4. INSERT đơn (PENDING_PAYMENT, hết hạn sau 10 phút) + saveAndFlush
5. Cộng hạn mức (PurchaseQuotas.reserve)                            → PURCHASE_LIMIT_EXCEEDED
6. Trừ vé GA theo ticketTypeId tăng dần (GaInventory.take)           → SOLD_OUT
7. Giữ toàn bộ ghế trong một lần gọi (SeatHoldService.hold)         → SEAT_UNAVAILABLE
8. INSERT các dòng order_items
```

Bước 5–7 đúng thứ tự khoá ở mục 8.3. Lỗi ở bất kỳ bước nào → exception → rollback cả 8 bước.

### 9.3. Idempotency key: bấm hai lần vẫn chỉ một đơn

**Vấn đề.** Khách bấm "Mua", mạng chập chờn, app không nhận được trả lời nên tự gửi lại. Nếu server tạo hai đơn,
khách bị giữ (và có thể bị trừ tiền) hai lần.

**Cách giải.** Client sinh một chuỗi ngẫu nhiên (UUID) cho **mỗi lần định mua** và gửi trong header
`Idempotency-Key`. Gửi lại cùng request thì dùng lại key cũ. Server:

- Lưu key trong `orders.idempotency_key`, có ràng buộc `UNIQUE (user_id, idempotency_key)`.
- Lưu thêm `request_hash` (mã SHA-256 của nội dung request, xem `RequestHash.java`). Cùng key mà khác nội dung là
  lỗi phía client → 422 `IDEMPOTENCY_KEY_REUSED`.

`ordering/OrderPlacement.java` (rút gọn):

```java
public PlacementResult place(UUID userId, String idempotencyKey, CreateOrderRequest request) {
    String hash = RequestHash.of(request);
    Optional<Order> existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    if (existing.isPresent()) {
        return replay(existing.get(), hash);              // đã có: trả lại đơn cũ
    }
    try {
        CreatedOrder created = creation.create(userId, idempotencyKey, hash, request);   // transaction riêng
        return new PlacementResult(created.order(), created.items(), false);
    } catch (DataIntegrityViolationException e) {
        if (!Constraints.isUniqueViolation(e, "uq_orders_user_idempotency_key")) {
            throw e;
        }
        // một request khác cùng key vừa commit trước ta: trả lại đơn của request đó
        Order order = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElseThrow();
        return replay(order, hash);
    }
}
```

**Vì sao cần cả `try/catch`?** Hai request cùng key đến cùng lúc, cả hai cùng thấy "chưa có đơn" ở dòng `findBy...`.
Đây lại là check-then-act. Lớp chặn thật là ràng buộc UNIQUE:

1. Request A `INSERT` đơn (chưa commit).
2. Request B `INSERT` cùng `(user_id, key)`. PostgreSQL bắt B **chờ** xem A commit hay rollback.
3. A commit → INSERT của B lỗi unique → B vào `catch`, đọc lại đơn của A, trả về (200).
4. Nếu A rollback (ví dụ hết vé) → INSERT của B thành công, B chạy tiếp như request mới.

Để cơ chế này đúng, **INSERT đơn phải là thao tác ghi đầu tiên** (bước 4 ở mục 9.2) và phải **flush ngay**
(`saveAndFlush`; `save` thường thì Hibernate để INSERT tới cuối transaction). Nếu giữ ghế trước rồi mới INSERT đơn,
request B sẽ gặp "ghế đã có người giữ" (chính A giữ!) và trả 409 thay vì trả lại đơn của A.

**Vì sao hai class?** `OrderPlacement` **không** có `@Transactional`. Sau một lỗi unique trong PostgreSQL,
transaction đó hỏng hẳn ("current transaction is aborted"), không chạy được câu `SELECT` đọc lại nữa. Nên `create`
chạy trong transaction riêng ở bean khác (`OrderCreationService`), lỗi thì transaction đó chết, còn `place` vẫn đọc
lại được. Đây cũng là chỗ nhắc lại bẫy self-invocation ở mục 2.8.

### 9.4. Huỷ đơn

`OrderCancellation.cancel` (đã trích ở mục 2.2):
1. Khoá đơn **của đúng người gọi** (`lockByIdAndUserId`): đơn người khác → không thấy → 404.
2. Đơn không còn `PENDING_PAYMENT` → 409 `ORDER_NOT_CANCELLABLE`.
3. `OrderRelease.release(order, CANCELLED, now)`: trả hạn mức → trả vé GA → trả ghế (đúng thứ tự khoá).

Khi trả ghế, điều kiện `WHERE order_id = :orderId` rất quan trọng: nếu ghế đã hết hạn và **người khác giữ lại**
(hết hạn lười), ghế đó mang `order_id` của người kia, câu lệnh không đụng vào.

### 9.5. Job dọn đơn hết hạn

`ordering/OrderExpiryJob.sweep()` chạy mỗi 5 giây:

```
cutoff = now - 1 phút (expiry-grace: chờ thêm vì webhook thanh toán có thể đang trên đường tới)
ids    = tìm tối đa 100 đơn PENDING_PAYMENT có expires_at < cutoff      ← không khoá
với mỗi id: expirer.expireOne(id, cutoff)                                ← MỖI ĐƠN MỘT TRANSACTION
```

`OrderExpirer.expireOne` khoá đơn bằng `FOR UPDATE SKIP LOCKED`:
- `SKIP LOCKED`: đơn đang bị khoá (webhook đang xử lý nó, hoặc một instance khác của app đang dọn nó) thì **bỏ qua**
  thay vì đứng chờ.
- Truy vấn lặp lại điều kiện `status = 'PENDING_PAYMENT' AND expires_at < :cutoff`: có thể đơn vừa được thanh toán
  giữa lúc tìm và lúc khoá.

Vì sao mỗi đơn một transaction? Gom 100 đơn vào một transaction thì job giữ khoá của rất nhiều người và loại vé,
theo thứ tự lộn xộn, dễ deadlock với khách đang mua. Mỗi vòng lặp còn có `try/catch` riêng: một đơn lỗi không làm
hỏng 99 đơn còn lại.

---

## 10. M2: thanh toán, webhook, hoàn tiền

### 10.1. Cổng thanh toán hoạt động thế nào

```
Khách ── POST /orders/{id}/payments ──► Ticket2h ── tạo phiên ──► MockPay
Khách ◄──────────── paymentUrl ──────── Ticket2h ◄── paymentUrl ── MockPay
Khách ── mở paymentUrl, bấm "Thành công" ──────────────────────────► MockPay
Ticket2h ◄─────── webhook có chữ ký: "payment.succeeded" ───────── MockPay
Khách ◄── chuyển trình duyệt về returnUrl ("đang xác nhận...") ──── MockPay
```

Hai đường MockPay báo kết quả:
- **Chuyển trình duyệt về `returnUrl?status=SUCCESS`**: tham số này **không có chữ ký**, khách sửa URL là đổi được.
  Ticket2h **không tin** nó: trang `/payments/return` (mặc định của web-app; core có trang dự phòng cùng đường dẫn)
  chỉ hiện "Đang xác nhận thanh toán…" rồi hỏi trạng thái đơn. Core gắn `orderId` vào `returnUrl` của từng phiên
  (`PaymentInitiation.returnUrlFor`), vì `merchantRef` MockPay gửi lại là id của payment, không phải của đơn.
- **Webhook**: MockPay gọi thẳng server-to-server, có chữ ký. Đây là **nguồn sự thật duy nhất**.

### 10.2. Tạo phiên thanh toán: không gọi HTTP trong transaction

`payment/PaymentInitiation.initiate` (không có `@Transactional`):

```
Transaction 1 (PaymentInitiationSteps.prepare):
  khoá đơn của đúng người gọi; không có → 404
  đơn phải PENDING_PAYMENT và chưa hết hạn                    → ORDER_NOT_PAYABLE
  đã có phiên đang dùng được → trả lại phiên đó (idempotent)
  đã tạo 3 phiên rồi                                           → PAYMENT_ATTEMPTS_EXCEEDED
  gia hạn giữ chỗ thêm 15 phút; INSERT payment (INITIATED)
commit

Ngoài transaction: gọi MockPay POST /api/v1/payments (timeout kết nối 2 giây, đọc 5 giây)

Transaction 2: lưu gatewayPaymentId + paymentUrl
               hoặc gọi lỗi → payment FAILED, trả 502 PAYMENT_GATEWAY_UNAVAILABLE
```

**Vì sao gọi MockPay ngoài transaction?** Gọi HTTP có thể mất 5 giây. Nếu đang giữ khoá đơn trong 5 giây đó,
webhook và job dọn đơn đều phải chờ theo. Tệ hơn, nếu transaction rollback thì lời gọi HTTP đã gửi đi không rút
lại được.

**Vì sao gia hạn?** Khách bấm thanh toán ở phút thứ 9 thì chỉ còn 1 phút để trả tiền. Gia hạn thêm 15 phút tránh
phần lớn ca "trả tiền xong thì đơn đã hết hạn".

`MockPayClient` dùng `RestClient` của Spring để gọi HTTP, có timeout. **Không bao giờ gọi mạng mà không đặt timeout**:
bên kia treo thì luồng của bạn treo theo.

### 10.3. Webhook và chữ ký HMAC

**Webhook** là khi một hệ thống khác gọi vào API của bạn để báo tin. Endpoint webhook không cần JWT (MockPay không
đăng nhập), nên ai cũng gọi được. Làm sao biết đúng là MockPay gọi?

**Chữ ký HMAC.** Ticket2h và MockPay cùng giữ một bí mật (`MOCKPAY_WEBHOOK_SECRET`). MockPay gửi kèm:

```
X-MockPay-Timestamp: 1793534400
X-MockPay-Signature: hex( HMAC_SHA256(secret, timestamp + "." + body) )
```

Ticket2h tự tính lại chữ ký từ body nhận được và so sánh. Kẻ giả mạo không có `secret` thì không tạo ra được chữ
ký đúng. Xem `payment/WebhookSignatureVerifier.java`, ba điểm quan trọng:

1. **Tính trên body thô dạng byte.** Controller đọc body bằng `request.getInputStream()`, không parse JSON trước.
   Parse rồi serialize lại thì chỉ cần khác một dấu cách hay thứ tự trường là chữ ký sai.
2. **Timestamp lệch quá 5 phút → từ chối.** Kẻ xấu chép được một webhook cũ cũng không gửi lại được (*replay attack*).
3. **So sánh bằng `MessageDigest.isEqual`, không dùng `equals`.** `equals` dừng ngay ở byte sai đầu tiên, nên thời gian
   trả lời lộ ra "đúng được mấy byte đầu" (*timing attack*). `isEqual` luôn so hết.

Thử tự tính chữ ký bằng terminal để thấy cơ chế:

```bash
printf '%s' '1793534400.{"a":1}' | openssl dgst -sha256 -hmac whsec_test
```

Kết quả phải trùng với giá trị trong `WebhookSignatureVerifierTest.known_vector_computed_with_openssl`.

### 10.4. Xử lý webhook

`payment/WebhookIngestion.handle`:

```
1. Kiểm tra chữ ký. Sai → ghi log (signature_valid = false) → 401
2. Parse JSON
3. Ghi payment_webhook_logs (transaction RIÊNG, REQUIRES_NEW: log còn lại kể cả khi bước 4 lỗi)
4. PaymentWebhookProcessor.process(event)   ← một transaction
5. Cập nhật kết quả vào log (PAID, DUPLICATE, REFUND_REQUESTED, ...)
```

Mã HTTP trả cho cổng thanh toán có ý nghĩa: cổng sẽ **gửi lại** nếu không nhận được 2xx.

| Tình huống | Mã | Lý do |
|---|---|---|
| Sai chữ ký | 401 | Không xử lý |
| Xử lý xong, **kể cả webhook trùng** | 200 | Để cổng ngừng gửi lại |
| Không tìm thấy payment, lệch số tiền | 200 + log cảnh báo | Gửi lại cũng không giải quyết được, cần người kiểm tra |
| Lỗi tạm thời (mất kết nối DB) | 500 | Để cổng gửi lại sau |

`payment/PaymentWebhookProcessor.succeeded` (rút gọn):

```java
Optional<Locked> found = lock(event);          // khoá ĐƠN trước, rồi mới khoá PAYMENT
...
if (payment.getStatus().isCaptured()) {
    return WebhookOutcome.DUPLICATE;           // webhook thứ 2, thứ 3: không làm gì nữa
}
if (event.amount().compareTo(payment.getAmount()) != 0 || !payment.getCurrency().equals(event.currency())) {
    payment.markAmountMismatch(...);           // số tiền lệch: không giao vé, cần người kiểm tra
    return WebhookOutcome.AMOUNT_MISMATCH;
}
payment.markSucceeded(event.gatewayTransactionId(), now);
if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
    List<UUID> heldSeats = seatHolds.lockHeldSeats(order.getId());   // khoá ghế rồi mới đếm
    if (heldSeats.size() == số ghế trong đơn) {
        seatHolds.markSold(order.getId());
        order.markPaid(now);
        outbox.write(... OrderPaid ...);       // mục 11
        return WebhookOutcome.PAID;
    }
}
payment.requestRefund(lý do, now);             // tiền đã trừ nhưng không giao vé được
return WebhookOutcome.REFUND_REQUESTED;
```

Những điều đáng học:
- **Webhook gửi 3 lần song song:** cả ba cùng chờ ở khoá đơn. Lần đầu chuyển `SUCCEEDED`. Hai lần sau thấy
  `isCaptured()` → `DUPLICATE`. Chỉ một lần có tác dụng (tiêu chí M2).
- **Tiền so bằng `compareTo`, không bằng `equals`:** `new BigDecimal("100.0").equals(new BigDecimal("100.00"))` là
  `false` vì khác số chữ số thập phân. `compareTo` so giá trị.
- **Kiểm tra số tiền:** không kiểm tra thì ai đó tạo phiên 1.000đ rồi dùng webhook của phiên đó cho đơn 4.600.000đ.

**Một cái bẫy Hibernate** (ghi trong `PaymentRepository.findOrderIdById`): muốn khoá đơn trước payment thì phải biết
`order_id` của payment trước. Nếu đọc cả entity `Payment` (`findById`) rồi mới khoá, lệnh khoá sau đó trả về **chính
object đã nằm trong bộ nhớ** với trạng thái đọc **trước khi khoá**. Hibernate không ghi đè entity nó đang quản lý.
Vì vậy code chỉ đọc đúng một cột `order_id` (projection), không tạo entity, rồi mới khoá.

### 10.5. Thanh toán trễ và hoàn tiền

Webhook "thành công" có thể đến khi đơn không còn nhận được:

| Khi webhook thành công đến | Kết quả |
|---|---|
| Đơn đang chờ, còn hạn | `PAID` |
| Đơn đã quá hạn nhưng job chưa dọn, ghế còn nguyên | `PAID` (không ai thiệt) |
| Đơn đã quá hạn, có ghế đã thuộc người khác | Hoàn tiền |
| Đơn `EXPIRED` hoặc `CANCELLED` | Hoàn tiền |
| Đơn đã `PAID` bằng phiên khác (trả hai lần ở hai tab) | Hoàn tiền phiên thứ hai |

Hoàn tiền **không** gọi MockPay ngay trong webhook. Payment chuyển `REFUND_PENDING`: đó là "ý định hoàn tiền" đã
được lưu chắc chắn. `payment/RefundJob` chạy mỗi 10 giây:

1. **Thuê việc (lease):** một transaction ngắn đánh dấu tối đa 20 payment "đang được xử lý trong 2 phút tới"
   (`UPDATE ... RETURNING`, có `SKIP LOCKED`).
2. Gọi MockPay hoàn tiền, ngoài transaction, với `idempotencyKey = payment.id`.
3. Thành công → `REFUNDED`. Lỗi → tăng `refund_attempts`, hẹn thử lại sau 1, 2, 5, 10, 30 phút rồi mỗi giờ.

Nếu app sập ngay sau khi gọi MockPay mà chưa kịp lưu `REFUNDED`? Hết 2 phút, payment được thử lại. Nhờ
`idempotencyKey`, MockPay nhận ra yêu cầu cũ và **không hoàn tiền hai lần**.

---

## 11. M3: outbox, Kafka, vé, QR, email, check-in, chuyển nhượng

### 11.1. Vấn đề "ghi hai nơi"

Đơn vừa `PAID`, ta cần (a) lưu trạng thái vào database và (b) báo cho phần phát vé qua Kafka. Hai cách viết sai:

```java
// SAI 1: gửi Kafka bên trong transaction
@Transactional
void confirm() {
    order.markPaid(now);
    kafka.send("order-events", orderPaid);   // gửi xong, rồi transaction rollback → phát vé cho đơn chưa trả
}

// SAI 2: gửi Kafka sau khi commit
void confirm() {
    tx.execute(s -> order.markPaid(now));
    kafka.send("order-events", orderPaid);   // app sập ngay trước dòng này → đơn PAID mãi không có vé
}
```

Database và Kafka là hai hệ thống khác nhau, không có transaction chung.

### 11.2. Transactional outbox

```
┌─────────── một transaction ───────────┐
│ UPDATE orders SET status = 'PAID'     │
│ INSERT INTO outbox_events (OrderPaid) │──► cùng commit hoặc cùng rollback
└───────────────────────────────────────┘
                 │
     OutboxRelay (mỗi 0,5 giây) đọc các dòng chưa gửi
                 ▼
              Kafka ──► TicketIssuer (phát vé) ──► outbox: TicketsIssued ──► Kafka ──► notification-service (email)
```

- `outbox/OutboxWriter.write(...)` có `Propagation.MANDATORY`: gọi ngoài transaction là ném lỗi ngay, nên không ai
  lỡ viết "SAI 2".
- `outbox/OutboxBatchPublisher.publishBatch(100)`: lấy tối đa 100 dòng `published_at IS NULL`
  (`FOR UPDATE SKIP LOCKED`), gửi từng dòng lên Kafka, **chờ Kafka xác nhận**, rồi đánh dấu `published_at`.
  Gửi lỗi thì ghi `attempts`, `last_error`, dừng lô, lần sau thử lại.

Hệ quả quan trọng: app có thể sập **sau** khi gửi Kafka nhưng **trước** khi đánh dấu `published_at`, nên lần sau
gửi lại. Outbox bảo đảm **ít nhất một lần** (at-least-once), không phải đúng một lần. Vì vậy **bên nhận phải chịu
được message trùng** (idempotent).

### 11.3. Kafka trong 5 phút

| Khái niệm | Nghĩa | Trong project |
|---|---|---|
| Topic | Một "kênh" message | `ticket2h.order-events`, `ticket2h.ticket-events` |
| Partition | Topic chia thành nhiều phần để xử lý song song; thứ tự chỉ đảm bảo trong một partition | 3 partition mỗi topic |
| Key | Message cùng key luôn vào cùng partition | key = id đơn → các event của một đơn đi đúng thứ tự |
| Header | Thông tin kèm message | `eventType: OrderPaid`, `eventId` |
| Consumer group | Nhóm cùng đọc một topic, mỗi message giao cho một thành viên | `ticket-issuer`, `ticket-notifier` |
| Offset | Vị trí đã đọc tới, Kafka nhớ cho từng group | |
| DLT (dead letter topic) | Nơi chứa message xử lý mãi không được | `ticket2h.order-events.DLT` |

Nhận message bằng `@KafkaListener` (`ticketing/TicketIssuer.java`):

```java
@KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "ticket-issuer")
void onOrderEvent(ConsumerRecord<String, String> record) {
    if (!Topics.ORDER_PAID.equals(messages.eventType(record))) {
        return;                                     // không phải việc của mình
    }
    OrderPaid event = messages.payload(record, OrderPaid.class);   // JSON → record
    issuance.issueFor(EventMessages.required(event.orderId(), "orderId"));
}
```

Listener ném exception thì sao? `outbox/KafkaConfig.kafkaErrorHandler` quyết định:
- Message hỏng (không phải JSON, thiếu trường) → `InvalidEventPayloadException` → vào DLT **ngay** (thử lại vô ích).
- Lỗi khác (DB tạm mất kết nối, mail server sập) → thử lại với thời gian chờ tăng dần (0,5 → 1 → 2 … tối đa 30 giây
  mỗi lần) trong 10 phút, rồi mới vào DLT.
- Lưới an toàn cuối: `ticketing/PaidOrderSweeper` mỗi phút tìm đơn `PAID` quá 5 phút mà chưa có vé, phát vé trực tiếp.

### 11.4. Phát vé an toàn khi message trùng

`ticketing/TicketIssuance.issueFor(orderId)`:

```
khoá đơn
đơn đã FULFILLED → đã phát rồi (message trùng), dừng
đơn không phải PAID → ghi cảnh báo, dừng
với mỗi order_item, với seq từ 1 đến quantity:
    INSERT INTO tickets (..., order_item_id, item_seq, status = 'VALID', token_version = 1)
    ON CONFLICT (order_item_id, item_seq) DO NOTHING
đơn → FULFILLED
outbox.write(TicketsIssued{orderId, userId, ticketIds})
```

Hai lớp chống trùng: trạng thái đơn (`FULFILLED` thì dừng), và ràng buộc `uq_tickets_order_item_seq` +
`ON CONFLICT DO NOTHING` (lỡ có ai sửa luồng sai, database vẫn không cho tạo thêm vé).

### 11.5. QR có chữ ký Ed25519

**Vì sao không dùng một chuỗi ngẫu nhiên lưu trong database?** Máy soát vé phải hỏi database mới biết QR thật hay
giả, mất mạng là chịu. Với chữ ký số, máy soát vé chỉ cần **khoá công khai** để kiểm tra, không cần mạng.

**Khoá bất đối xứng** gồm một cặp:
- **Khoá riêng (private key)**: chỉ server giữ (`QR_PRIVATE_KEY` trong `.env`), dùng để **ký**.
- **Khoá công khai (public key)**: phát cho mọi máy soát vé (`GET /ticket-keys`), chỉ dùng để **kiểm tra**.
  Mất máy soát vé cũng không làm giả được vé. (Với HMAC ở mục 10.3, ai kiểm tra được thì cũng ký được, vì dùng chung một bí mật.)

Định dạng token trong `ticketing/QrTokenCodec.java`:

```
TR1 . k1 . <payload base64url> . <chữ ký base64url>
 │    │       │                      └── Ed25519(khoá riêng, payload), 64 byte
 │    │       └── 36 byte: ticketId (16) + eventId (16) + tokenVersion (4)
 │    └── id của khoá: đổi khoá định kỳ mà vé cũ vẫn kiểm tra được
 └── phiên bản định dạng
```

- `tokenVersion` tăng mỗi lần chuyển nhượng → QR cũ không còn khớp.
- Chữ ký Ed25519 **tất định** (cùng dữ liệu, cùng chữ ký), nên token tính lại được bất cứ lúc nào, không cần lưu.
- **Bẫy base64:** chữ ký 64 byte mã hoá ra 86 ký tự; ký tự cuối có 4 bit thừa mà bộ giải mã của Java bỏ qua. Đổi
  ký tự cuối sang một ký tự khác vẫn có thể ra **đúng** chữ ký cũ. Code chống bằng `decodeCanonical`: giải mã xong
  mã hoá lại, phải khớp đúng chuỗi gốc. Test `QrTokenCodecTest` thử đổi **mọi ký tự ở mọi vị trí** (khoảng 9.000
  biến thể) và yêu cầu tất cả đều bị từ chối.

Chữ ký **không** chống được chụp màn hình QR rồi chia sẻ. Cái chống là: **mỗi vé chỉ check-in được một lần**.

### 11.6. Email vé (nay ở notification-service)

Email vé đã tách khỏi core thành service riêng, repo [notification-service](https://github.com/ticket2h/notification-service). Luồng:

```
core: outbox TicketsIssued {orderId, userId, ticketIds} ──► Kafka ticket2h.ticket-events
                                                               │
notification-service: TicketEventsListener ◄───────────────────┘
   → TicketNotificationService
       → GET /internal/users/{userId}        (core trả email, tên)
       → GET /internal/tickets?ids=...       (core trả thông tin vé + token QR của vé còn VALID)
       → TicketEmailSender: vẽ ảnh QR (ZXing), gửi SMTP
```

Event chỉ mang id, không mang token QR: token là "chìa khoá vào cửa", không nên nằm trong bảng outbox hay log Kafka
(ADR-011 ở [infra/docs/adr](https://github.com/ticket2h/infra/tree/main/docs/adr)). Phía core, hai controller `InternalUserController` và `InternalTicketController`
phục vụ các lời gọi này; chúng nằm sau một chuỗi bảo mật riêng (`SecurityConfig.internalApiFilterChain`) chỉ nhận
header `X-Internal-Api-Key` của service được cấp khoá, JWT của người dùng không có tác dụng.

Chống gửi trùng (trong database riêng của notification-service) bằng bảng `notification_deliveries` với
`dedupe_key = "tickets-issued:<orderId>"`:

```
INSERT ... ON CONFLICT (dedupe_key) DO NOTHING      ← tạo dòng PENDING nếu chưa có
đọc lại: đã SENT → bỏ qua
gửi email (ngoài transaction)
thành công → SENT;  lỗi → tăng attempts, ném lại exception để Kafka thử lại
```

Service sập sau khi gửi email mà chưa kịp đánh dấu `SENT`? Message được xử lý lại → khách nhận **hai** email. Đây là
lựa chọn có chủ đích: email trùng ít hại hơn email bị mất. Ngược lại, hoàn tiền thì phải đúng một lần, nên dựa vào
idempotency key phía cổng thanh toán.

core-service không chạy (hoặc mail server sập) thì notification ném exception, Kafka thử lại lùi dần tới 10 phút.
Ngược lại, notification-service sập không ảnh hưởng gì tới việc bán vé: event nằm chờ trong Kafka, bật lại là gửi bù.

### 11.7. Check-in

`ticketing/CheckinService.checkin(staffId, qrToken, gate)`:

```
1. Giải mã + kiểm tra chữ ký QR. Lỗi → INVALID_TOKEN
2. Nhân viên có được phân công sự kiện này? Không → WRONG_EVENT_STAFF (controller trả 403)
3. Một câu lệnh có điều kiện:
     UPDATE tickets SET status = 'USED', checked_in_at = :now, checked_in_gate = :gate, ...
     WHERE id = :ticketId AND event_id = :eventId AND status = 'VALID' AND token_version = :tokenVersion
   1 dòng → OK
   0 dòng → đọc vé để giải thích: USED → ALREADY_USED (kèm giờ, cổng lần đầu),
            khác version → TOKEN_REVOKED (vé đã chuyển nhượng), VOID → VOIDED
4. Ghi checkin_logs cho MỌI lượt quét, kể cả thất bại
```

Hai cổng quét cùng một QR cùng lúc: cả hai chạy câu UPDATE trên cùng một dòng, PostgreSQL bắt chạy lần lượt. Câu
sau đánh giá lại `status = 'VALID'` trên dữ liệu mới: đã `USED` → 0 dòng → `ALREADY_USED`. Đúng kỹ thuật 2 ở mục 8.4.

Kết quả nghiệp vụ (vé đã dùng, QR bị thu hồi) trả **200** kèm `result`, không phải lỗi 4xx. Nhân viên cần biết
**vì sao** không cho khách vào; đó không phải lỗi hệ thống. Method trả về kết quả thay vì ném exception, vì exception
sẽ rollback luôn dòng log vừa ghi.

### 11.8. Chuyển nhượng vé

`ticketing/TicketTransferService.transfer`:

```sql
UPDATE tickets
SET owner_user_id = :toUserId, token_version = token_version + 1
WHERE id = :ticketId AND owner_user_id = :fromUserId AND status = 'VALID'
RETURNING token_version
```

`RETURNING` trả về giá trị mới ngay trong câu UPDATE. 0 dòng → đọc lại vé: không còn là chủ → 404, đã dùng → 409.

Đua giữa chuyển nhượng và check-in: check-in đòi `token_version` khớp, chuyển nhượng đòi `status = 'VALID'`. Hai câu
lệnh nhắm cùng một dòng nên chạy lần lượt, bên đến sau thấy điều kiện của mình không còn đúng. Test
`transfer_racing_checkin_always_has_exactly_one_winner` chạy 50 vòng và luôn đúng một bên thắng.

---

## 12. M4: dữ liệu lớn

M3 kết thúc với một hệ thống **đúng**. M4 hỏi một câu khác: hệ thống đã chạy 2 năm, có 2 triệu tài khoản, 10 triệu
đơn, 30 triệu lượt quét, thì cái gì còn nhanh? Lộ trình đặt 4 tiêu chí, mỗi tiêu chí là một test trong `acceptance`:

| Tiêu chí | Test | Kỹ thuật chính | Mục |
|---|---|---|---|
| Báo cáo một sự kiện 200.000 đơn dưới 1 giây | `SalesReportPerformanceIT` | Bảng tổng hợp + hàng đợi | 12.3 |
| Trang thứ 10.000 của danh sách vẫn nhanh | `OrderPagingIT` | Phân trang keyset | 12.4 |
| Xuất 500.000 dòng với heap 256MB | `ExportMemoryIT` | Đọc theo lô / theo luồng, ghi thẳng ra response | 12.5 |
| Import 50.000 ghế, báo đúng dòng lỗi | `SeatImportIT` | Kiểm tra hết rồi mới ghi, JDBC batch | 12.6 |

Thêm hai việc không có test riêng mà có lý do riêng: công cụ sinh dữ liệu (12.1) và partition bảng log (12.7).

Muốn tự thấy mọi con số trong chương này: `../infra/scripts/datagen.sh` (khoảng 10 phút), rồi
`docker exec -it ticket2h-postgres-1 psql -U core` và chạy các câu `EXPLAIN` ở mục 12.2.

### 12.1. Sinh dữ liệu giả cho đúng (`dev/datagen/`)

Với 100 dòng, câu SQL nào cũng chạy trong 1 ms, nên không đo được gì. Công cụ sinh dữ liệu là bước đầu tiên của M4,
và có ba cái khó:

1. **Hai database.** Tài khoản ở identity, đơn ở core, và service không được đọc database của nhau. Cách giải: mỗi
   service sinh phần của mình, hai bên tính id theo cùng công thức (`DatagenIds`): khách thứ i luôn là
   `0d000000-0000-4000-8000-{i viết hex 12 chữ số}`. Đơn core sinh trỏ đúng tài khoản identity đã sinh, không bên nào
   gọi bên nào. Công thức là hợp đồng (`infra/docs/contracts.md` mục 6), có test ở cả hai repo.
2. **Nhanh.** 100 triệu dòng mà INSERT từng câu thì mất nhiều giờ. `CopyWriter` dùng `COPY ... FROM STDIN` của
   PostgreSQL: gửi một luồng CSV, database ghi thẳng vào bảng, không phân tích từng câu lệnh. Đây là API riêng của
   driver (`PGConnection.getCopyAPI()`), nên `pom.xml` đổi driver từ phạm vi `runtime` sang `compile`.
3. **Khoá ngoại và bộ nhớ.** `order_items` trỏ tới `orders`, `tickets` trỏ tới `order_items` và `seats`... nên phải ghi
   theo thứ tự. Nhưng giữ 200.000 đơn của sự kiện lớn trong bộ nhớ để ghi nhiều bảng thì tốn. Mẹo:
   `EventPlan.order(k)` tính đơn thứ k **chỉ từ (seed, sự kiện, k)**, nên hỏi lại bao nhiêu lần cũng ra cùng kết quả.
   `CoreDataGenerator.writeEvent` đi qua danh sách đơn nhiều lượt, mỗi lượt ghi một bảng:

```java
long rows = writeCatalog(c, plan) + writeOrders(c, plan) + writeSeats(c, plan) + writeItems(c, plan)
        + writePayments(c, plan) + writeTickets(c, plan) + writeStaff(c, plan)
        + writeCheckins(c, plan, extraRate);
c.commit();   // một sự kiện, một transaction
```

Một bài học lúc làm công cụ này: thử cho id đơn **hoàn toàn ngẫu nhiên** (như `UUID.randomUUID()`), sinh dữ liệu chậm
đi khoảng 4 lần. Index B-tree của khoá chính được sắp theo id; id ngẫu nhiên thì mỗi INSERT rơi vào một trang ngẫu
nhiên của index, mà index của bảng 10 triệu dòng không nằm vừa bộ nhớ đệm, nên gần như lần nào cũng phải đọc trang từ
đĩa. Id tăng dần thì mọi INSERT ghi vào cuối index. Đây là lý do UUID version 7 (48 bit đầu là thời gian) ra đời. Xem
chú thích của `DatagenIds.of` để biết cách công cụ dung hoà.

Dữ liệu sinh không đi qua nghiệp vụ (không gọi `GaInventory.take`, không giữ hạn mức), nên những bảng **suy ra được**
từ bảng gốc (tồn kho GA còn lại, bảng tổng hợp doanh thu, hạn mức mua) được tính từ kế hoạch hoặc bằng SQL sau cùng.
`CoreDataGeneratorIT` chạy lại bộ bất biến của M2 (`Invariants`) trên dữ liệu sinh: nếu công cụ sinh ra một ghế thuộc
hai vé, test đỏ.

### 12.2. `EXPLAIN ANALYZE`: hỏi PostgreSQL nó đã làm gì

Trước khi tối ưu, phải đo. `EXPLAIN (ANALYZE, BUFFERS)` chạy câu lệnh thật và in kế hoạch kèm số liệu thật:

```
Limit  (actual time=0.079..0.083 rows=21 loops=1)
  ->  Index Scan using idx_users_created on users  (actual time=0.078..0.081 rows=21 loops=1)
        Index Cond: (ROW(created_at, id) < ROW('2025-...', '0d00...'))
```

Đọc từ trong ra ngoài (dòng thụt vào nhiều nhất chạy trước). Ba thứ cần nhìn:

| Thấy | Nghĩa |
|---|---|
| `Seq Scan` | Đọc cả bảng. Với bảng 10 triệu dòng mà chỉ cần vài nghìn dòng: thiếu index phù hợp |
| `Index Scan` / `Index Only Scan` | Đi theo index. `Index Cond` là điều kiện dùng để nhảy vào index; `Filter` là điều kiện lọc sau khi đã đọc dòng |
| `actual ... rows=` | Số dòng nút đó thật sự trả ra. Trả 21 dòng mà nút con đọc 200.000 dòng: đang phí công |

Các ADR-016…019 có số đo thật trên dữ liệu sinh. Hai ví dụ dễ thấy nhất: báo cáo doanh thu tính thẳng khi chưa có
index nào bắt đầu bằng `event_id` (trước M4) là `Seq Scan` trên 10 triệu đơn; trang thứ 10.000 bằng OFFSET có một nút
đọc 200.000 dòng để trả 20.

### 12.3. Báo cáo dưới 1 giây: bảng tổng hợp và hàng đợi (`reporting/`, ADR-016)

Cách tự nhiên nhất là tính mỗi lần xem:

```sql
SELECT (o.paid_at AT TIME ZONE e.time_zone)::date, sum(oi.quantity), sum(oi.quantity * oi.unit_price)
FROM orders o JOIN events e ON e.id = o.event_id JOIN order_items oi ON oi.order_id = o.id
WHERE o.event_id = :eventId AND o.status IN ('PAID', 'FULFILLED')
GROUP BY 1
```

Đúng, nhưng thời gian tăng theo số đơn của sự kiện. Cách của M4: **tính trước**. Bảng `sales_daily_items` giữ sẵn tổng
vé và doanh thu theo (sự kiện, ngày, kênh, loại vé): sự kiện 200.000 đơn chỉ còn vài chục dòng, báo cáo chỉ cộng vài
chục dòng đó.

Câu hỏi khó là **cập nhật bảng tổng hợp lúc nào**. Cộng ngay lúc webhook báo trả tiền? Mọi đơn của cùng sự kiện, cùng
ngày, cùng kênh rơi vào **cùng một dòng** tổng hợp: lúc mở bán, mọi webhook xếp hàng chờ khoá dòng đó, đúng điểm nghẽn
của bộ đếm GA (mục 8.4, ADR-003), mà lại nằm ngay trên đường thanh toán. Nên M4 tách làm hai:

```
webhook (PaymentWebhookProcessor), MỘT transaction:
    order.markPaid(now)
    outbox.write(... OrderPaid ...)
    salesRollup.enqueue(orderId)      → INSERT vào sales_rollup_queue (mỗi đơn một dòng, không ai tranh)

job mỗi 5 giây (SalesRollup.applyPending), MỘT câu lệnh:
    lấy và XOÁ 500 đơn khỏi hàng đợi    (DELETE ... RETURNING, FOR UPDATE SKIP LOCKED)
    cộng vé/doanh thu theo loại vé       (INSERT ... ON CONFLICT DO UPDATE SET tickets = tickets + EXCLUDED.tickets)
    cộng số đơn theo ngày, kênh          (INSERT ... ON CONFLICT DO UPDATE)
```

Mở `SalesRollupStore.APPLY_BATCH` để xem câu lệnh thật. Vì sao **một** câu lệnh? Câu lệnh là nguyên tử: hoặc xoá khỏi
hàng đợi và cộng cùng xảy ra, hoặc không gì cả. Không bao giờ có trạng thái "đã cộng mà chưa xoá" (cộng hai lần) hay
"đã xoá mà chưa cộng" (mất đơn). Và đơn vào hàng đợi trong cùng transaction với lúc chuyển PAID, giống outbox ở mục
11.2: trả tiền commit thì hàng đợi có đơn, rollback thì không. Đánh đổi: báo cáo chậm hơn thực tế tối đa 5 giây.

Hai chi tiết nhỏ nhưng là câu hỏi phỏng vấn:

- **Bẫy múi giờ.** `sale_date = (paid_at AT TIME ZONE e.time_zone)::date`. Server chạy giờ UTC; đơn trả tiền lúc
  23:30 giờ Việt Nam là 16:30 UTC cùng ngày, nhưng đơn lúc 00:30 giờ Việt Nam là 17:30 UTC **ngày hôm trước**. Test
  `sale_day_follows_the_event_time_zone_not_utc`.
- **Cho người dùng chọn cột sắp xếp mà không bị SQL injection.** `ORDER BY` không dùng tham số `?` được (tham số chỉ
  thay giá trị, không thay tên cột), nên dễ bị ghép chuỗi. Cách làm: nhận `sort` là enum `SalesSort`
  (Spring trả 400 với giá trị lạ), rồi `SalesReportStore.orderBy` dịch từng giá trị enum sang một đoạn SQL viết sẵn.
  Chuỗi người dùng gửi lên không bao giờ chạm tới câu SQL. Test `unknown_sort_values_are_rejected_instead_of_reaching_sql`
  gửi `sort=revenue; DROP TABLE orders`.

### 12.4. Phân trang keyset (`ordering/OrderListStore`, identity `UserSearchImpl`, ADR-018)

`LIMIT 20 OFFSET 199980` nghĩa là "đọc 200.000 dòng, bỏ 199.980 dòng đầu, trả 20 dòng". Trang càng sâu càng chậm. Và
khi có dòng mới chen vào đầu danh sách giữa hai lần bấm "trang sau", mọi dòng bị đẩy xuống: trang sau lặp lại dòng
cuối của trang trước.

Keyset hỏi theo kiểu khác: "cho tôi 20 dòng **đứng sau dòng cuối tôi vừa thấy**":

```sql
SELECT ... FROM orders
WHERE event_id = :eventId AND (created_at, id) < (:afterAt, :afterId)   -- dòng cuối trang trước
ORDER BY created_at DESC, id DESC
LIMIT 21                                                                 -- thừa 1 để biết còn trang sau
```

Với index `(event_id, created_at DESC, id DESC)`, `(created_at, id) < (...)` là phép so sánh **cả hàng** (row value):
PostgreSQL dùng nó làm `Index Cond`, nhảy thẳng tới đúng chỗ trong index rồi đọc 21 dòng. Trang 1 hay trang 10.000 đều
đọc 21 dòng. Ba chi tiết:

- `id` đứng cuối thứ tự để thứ tự là **duy nhất**: nhiều đơn cùng `created_at` (cùng micro giây) mà chỉ so
  `created_at` thì sẽ lặp hoặc sót đúng các đơn đó. Test `walking_next_cursors_visits_every_order_once_even_with_identical_timestamps`.
- Client không cần biết bên trong: server gửi `nextCursor` là chuỗi base64 của `created_at|id` (`KeysetCursor`),
  client gửi lại nguyên văn.
- Không có tổng số trang: `count(*)` trên 2 triệu dòng ở mỗi trang tốn hơn chính việc lấy trang. Web đổi thanh số trang
  thành nút "Xem thêm".

Kiểm chứng không cần đồng hồ: `OrderPagingIT` đọc `EXPLAIN (ANALYZE, FORMAT JSON)` và đếm số dòng các nút quét đã đọc:
keyset 21 dòng, OFFSET hơn 200.000 dòng. Test kiểu này không bị "máy CI chậm hôm nay" làm đỏ oan.

### 12.5. Xuất 500.000 dòng với 256MB heap (`ticketing/AttendeeExport`, `CheckinLogExport`, ADR-017)

Cách sai: đọc hết vào `List<Row>` rồi ghi. 500.000 dòng × vài trăm byte là hàng trăm MB. Cách đúng: **không bao giờ
giữ nhiều hơn một ít dòng trong bộ nhớ**, đọc tới đâu ghi ra response tới đó. Có hai cách đọc như vậy, và repo dùng cả
hai, có chủ ý:

| | Danh sách khách (`AttendeeExport`) | Lịch sử soát vé (`CheckinLogExport`) |
|---|---|---|
| Cần gọi service khác giữa chừng? | Có: tên, email ở identity | Không: tên nhân viên hỏi một lần trước |
| Cách đọc | Theo **lô keyset**: mỗi lô 1.000 vé là một câu SELECT tự commit | Theo **luồng**: một transaction chỉ đọc, `fetchSize = 1000` |
| Vì sao | Quy ước "không gọi HTTP trong transaction" (mục 10.2) | Đơn giản hơn, một câu SQL |

Chi tiết về "đọc theo luồng" mà nhiều người không biết: driver PostgreSQL **chỉ** đọc theo cursor (mỗi lần 1.000 dòng)
khi autocommit tắt, tức là phải nằm trong transaction. Ngoài transaction, `setFetchSize` bị bỏ qua và driver tải hết kết
quả vào bộ nhớ trước khi trả dòng đầu. Vì vậy `CheckinLogReader.read` có `@Transactional(readOnly = true)`, tách bean
riêng để annotation có hiệu lực (mục 2.8).

Ghi ra đâu? Thẳng vào `response.getOutputStream()` trong luồng xử lý request (`OrganizerExportController`). Header chỉ
được đặt sau khi đã kiểm tra quyền và đọc xong lô đầu: lỗi trước đó (403, identity sập → 502) vẫn trả JSON như mọi API.
Lỗi sau khi đã ghi dòng đầu thì không đổi được mã HTTP nữa, nên ghi một dòng `# LỖI ...` ở cuối file.

Một lỗ hổng bảo mật ít người nghĩ tới: **CSV injection**. Khách đặt tên `=HYPERLINK("http://...")`, ban tổ chức mở file
bằng Excel, Excel chạy công thức. `CsvWriter` thêm `'` trước mọi ô bắt đầu bằng `= + - @`.

Test chứng minh 256MB thế nào? `pom.xml` có execution `export-memory` của failsafe: chạy riêng `ExportMemoryIT` trong một
JVM `-Xmx256m`. App và test cùng nằm trong 256MB đó. Thử sửa `AttendeeExport` gom mọi dòng vào một `List` rồi mới ghi:
test hết heap và đỏ.

### 12.6. Nhập 50.000 ghế từ file (`catalog/SeatImport`, ADR-020)

Hai quyết định:

- **Tất cả hoặc không gì cả.** File có lỗi thì không thêm ghế nào, và báo **mọi** dòng lỗi một lần (dòng 1 là tiêu đề,
  đúng số dòng người dùng thấy trong Excel). Nếu ghi phần đúng, bỏ phần sai, thì sửa file rồi tải lại sẽ gặp lỗi "trùng"
  ở mọi dòng đã ghi lần trước.
- **Hai bước.** Bước 1 không cần database: đọc file (`CsvReader`), kiểm tra từng dòng một mình. Bước 2 trong một
  transaction (`OrganizerCatalogService.importSeats`): khoá sự kiện, kiểm tra những gì cần database (loại vé có thật,
  ghế đã có, trùng dòng nào trong file), rồi INSERT.

INSERT 50.000 dòng từng câu một là 50.000 lượt đi về giữa app và database. `CatalogStore.insertSeats` dùng
`batchUpdate` (JDBC batch, mỗi lần 1.000 dòng), cộng với `reWriteBatchedInserts=true` trong `application.yml`: driver gộp
một lô thành vài câu `INSERT ... VALUES (...), (...), ...`. Câu hỏi trong lộ trình: vì sao `GenerationType.IDENTITY` làm
Hibernate không batch INSERT được? Vì với IDENTITY, database mới cấp id lúc INSERT, mà Hibernate cần biết id ngay khi
`persist` để quản lý entity, nên phải chạy từng câu INSERT và đọc id về. Bảng nghiệp vụ ở đây dùng UUID sinh ở Java
(`AssignedIdEntity`), nên không gặp chuyện đó.

### 12.7. Chia bảng log theo tháng (`V117`, `ticketing/CheckinLogPartitions`, ADR-019)

`checkin_logs` chỉ thêm, không sửa, và gần như luôn được đọc theo khoảng thời gian. Sau 2 năm, 30 triệu dòng. Partition
theo `scanned_at`: mỗi tháng một bảng con (`checkin_logs_2026_11`...). Truy vấn có điều kiện thời gian chỉ đọc bảng con
liên quan (**partition pruning**: thấy trong `EXPLAIN`, chỉ có tên một bảng con). Bỏ dữ liệu cũ là `DROP` một bảng con
thay vì `DELETE` hàng triệu dòng.

Ba điều phải nhớ khi làm partition:

1. Khoá chính phải chứa cột dùng để chia: `PRIMARY KEY (id, scanned_at)`.
2. Bảng con cho tháng sắp tới phải có trước khi tới tháng đó: job `CheckinLogPartitions` mỗi ngày giữ sẵn 3 tháng.
3. Bảng `checkin_logs_default` hứng mọi dòng không thuộc tháng nào, để check-in ở cổng không bao giờ lỗi chỉ vì thiếu
   bảng con. Đọc phần "Hệ quả" của ADR-019 để biết cái giá của nó.

Đọc `V117__m4_checkin_logs_partition.sql`: chuyển một bảng đang có dữ liệu sang bảng partition là đổi tên bảng cũ, tạo
bảng mới, chép dữ liệu, chỉnh lại sequence, xoá bảng cũ, tất cả trong một transaction của Flyway.

### 12.8. Tóm tắt

| Vấn đề | Đừng | Làm |
|---|---|---|
| Báo cáo trên bảng rất lớn | Tính lại mỗi lần xem; cộng thẳng vào dòng tổng hợp trong transaction nóng | Bảng tổng hợp, cập nhật qua hàng đợi ghi cùng transaction |
| Người dùng chọn cột sắp xếp | Ghép chuỗi vào `ORDER BY` | Enum → đoạn SQL viết sẵn |
| Danh sách sâu | `OFFSET`, `count(*)` mỗi trang | Keyset `(thời gian, id) < (...)` + index đúng thứ tự |
| Xuất file lớn | Gom vào `List`/chuỗi | Đọc theo lô keyset (cần gọi mạng) hoặc theo luồng với fetch size trong transaction (không cần) |
| Nhập file lớn | Ghi từng dòng, bỏ dòng lỗi | Kiểm tra hết, báo mọi dòng lỗi, ghi theo lô trong một transaction |
| Bảng log chỉ thêm | Một bảng khổng lồ, `DELETE` dữ liệu cũ | Partition theo thời gian, bảng default làm lưới an toàn |
| Đo hiệu năng | Đoán | Dữ liệu cỡ thật + `EXPLAIN ANALYZE`; test đếm dòng đã đọc thay vì đo giờ |

---

## 13. M5: thời gian thực và giờ mở bán

M4 hỏi "dữ liệu lớn thì còn nhanh không". M5 hỏi "20:00 mở bán, hàng nghìn người bấm cùng một giây thì sao", và
"người khác vừa giữ ghế thì mình có thấy ngay không". Lộ trình đặt hai tiêu chí:

| Tiêu chí | Ở đâu | Kỹ thuật chính | Mục |
|---|---|---|---|
| Ghế đổi trạng thái hiện ở máy khác dưới 1 giây, kể cả khi hai máy nối vào hai instance khác nhau | `SeatMapLiveIT` | SSE, Redis pub/sub, số phiên bản của ghế | 13.2 |
| Load test giờ mở bán: không bán trùng, không 5xx, p95 đạt mục tiêu | `../infra/scripts/loadtest.sh` (k6) | Phòng chờ, giới hạn request, cache, hai instance | 13.3–13.7 |

Lần đầu tiên hệ thống có Redis. Quy tắc xuyên suốt: **Redis chỉ giữ thứ mất được**. Đơn, vé, tiền, trạng thái ghế
vẫn chỉ nằm trong PostgreSQL; Redis sập thì bán vé vẫn chạy (chậm hơn, ít tiện hơn), không sai.

### 13.1. Redis trong 5 phút

Redis là một server giữ dữ liệu trong RAM, mỗi khoá một giá trị. M5 dùng năm thứ:

| Thứ | Lệnh | Dùng ở |
|---|---|---|
| Chuỗi, số đếm | `GET`, `SET key value NX PX 5000` (chỉ đặt nếu chưa có, hết hạn sau 5 giây), `INCR` | Số thứ tự sơ đồ ghế, khoá "giây này đã có instance cho người vào" |
| Hash | `HSET`, `HMGET` | Xô token của giới hạn request (`tokens`, `at`) |
| Sorted set (tập có điểm, luôn sắp theo điểm) | `ZADD`, `ZRANK` (vị trí), `ZPOPMIN n` (lấy n phần tử điểm nhỏ nhất), `ZRANGEBYSCORE` | Hàng chờ của phòng chờ |
| Pub/sub | `PUBLISH kênh tin`, `PSUBSCRIBE mẫu*` | Phát thay đổi ghế tới mọi instance |
| Script Lua | `EVAL` | Mọi thao tác nhiều bước |

Điều quan trọng nhất: **Redis chạy từng lệnh một, và chạy cả một script Lua như một lệnh**. Đọc, tính, ghi trong một
script thì không instance nào chen vào giữa được. Đây là cách M5 tránh race condition mà không cần khoá:

```lua
-- SeatChangeBus.PUBLISH: tăng số thứ tự và phát trong cùng một bước
local seq = redis.call('INCR', KEYS[1])
redis.call('PUBLISH', KEYS[2], seq .. '|' .. ARGV[1])
return seq
```

Trong Java, script là một `RedisScript<Long>` gọi qua `StringRedisTemplate.execute(script, keys, args...)`. Khoá đặt
tên có tiền tố `t2h:` và phần `{eventId}` trong ngoặc nhọn (hash tag): nếu sau này chạy Redis Cluster, mọi khoá của
một sự kiện nằm cùng một node, script mới chạm được tất cả.

Thử tay: `docker compose exec redis redis-cli`, rồi `PSUBSCRIBE 't2h:seatmap:changes:*'` và giữ một ghế trên web.

### 13.2. Sơ đồ ghế trực tiếp (`inventory/SeatChange*`, `SeatMapHub`, ADR-024)

Đường đi của một thay đổi:

```
POST /orders (instance A)
  └─ SeatHoldService.hold: UPDATE seats ... version = version + 1 RETURNING id, section, status, version
       └─ SeatChangeRecorder: gom các ghế đổi, đăng ký "sau khi commit" (TransactionSynchronization)
            └─ commit xong → SeatChangeBus.publish: bỏ vào hàng đợi, luồng request đi tiếp, không chờ Redis
                 └─ luồng phát: script Lua INCR + PUBLISH ─► Redis ─► mọi instance (A, B...) đang PSUBSCRIBE
                                                                     └─ SeatMapHub.accept: gom theo sự kiện
                                                                          └─ mỗi 300 ms: một event "seats" cho mỗi trình duyệt (SSE)
```

Bốn câu hỏi và cách code trả lời:

1. **Vì sao phát SAU commit?** Phát trong transaction mà transaction rollback (ghế thứ hai đã có người) thì trình duyệt
   thấy một thay đổi chưa từng xảy ra. `TransactionSynchronization.afterCommit` chỉ chạy khi commit thành công.
2. **Vì sao SSE mà không WebSocket?** Sơ đồ ghế chỉ cần một chiều (server → trình duyệt). SSE là HTTP thường:
   `SseEmitter` của Spring MVC, `EventSource` của trình duyệt tự nối lại khi mất mạng, nginx chỉ cần không gom bộ đệm
   (core gửi header `X-Accel-Buffering: no`). WebSocket đáng dùng khi cần hai chiều (chat, game).
3. **Hai instance phát lệch thứ tự thì sao?** A giữ ghế X, B trả ghế X; luồng phát của A chậm hơn một chút thì trình
   duyệt nhận "trống" rồi "đã giữ". Số thứ tự chung không cứu được (nó là thứ tự phát, không phải thứ tự commit). Cách
   giải là `seats.version` tăng ngay trong câu UPDATE: trình duyệt chỉ áp thay đổi có version lớn hơn cái đang có
   (`web-app/src/features/events/liveSeats.ts`), nhận lẫn thứ tự vẫn đúng.
4. **Mất mạng rồi nối lại thì sao?** Trình duyệt mở luồng trước, nhận `hello {seq}`, rồi tải ảnh chụp `/seats`. Ảnh
   chụp mang header `Seat-Map-Seq`, đọc **trước** khi đọc database: thay đổi số k chỉ được phát sau khi commit, nên mọi
   thay đổi có số ≤ `Seat-Map-Seq` đã nằm trong ảnh chụp. Ảnh chụp cũ hơn `hello` thì tải lại. Nối lại sau khi mất
   mạng là một `hello` mới, tức là lại tải đủ. Instance lỡ message (Redis mất kết nối) thấy số nhảy cóc và gửi `resync`.

Đọc `SeatMapLiveIT.viewer_on_one_instance_sees_changes_made_on_another`: test **khởi động thêm một app core thật**
(`SpringApplicationBuilder`, cổng ngẫu nhiên, cùng PostgreSQL/Kafka/Redis của test) để chứng minh tiêu chí hai instance.

### 13.3. Cache làm mới ở nền (`common/cache/`, ADR-025)

Trang sự kiện và sơ đồ ghế giống nhau cho mọi người và cũ vài giây không sao (đặt vé luôn kiểm tra lại trong
database). Từ khoá của lộ trình là `@Cacheable(sync = true)`: lúc hết hạn chỉ một request tải lại, các request khác
**chờ** kết quả đó. Chặn được việc 10.000 request cùng đổ vào database, nhưng mỗi lần hết hạn mọi người đều chờ.

`RefreshAheadCache` dùng `refreshAfterWrite` của Caffeine: quá hạn thì request kế tiếp vẫn nhận ngay bản cũ, Caffeine
tải bản mới trên luồng nền. Chỉ lần đầu tiên mới phải chờ.

Bài học đo được ở M5: bản đầu đặt `@Cacheable` và `@Transactional` trên cùng một method. Hai annotation là hai lớp
proxy (AOP), không khai báo thứ tự thì transaction có thể chạy trước cache: **request trúng cache vẫn mượn một kết nối
database** để mở transaction rồi đóng. Lúc mở bán, kết nối bận vì các đơn đang chờ khoá ghế, và trang sự kiện vốn đã
có trong bộ nhớ cũng chậm theo. Muốn dùng hai annotation cùng nhau thì đặt `@EnableCaching(order = ...)` nhỏ hơn thứ
tự của transaction, hoặc như ở đây: phần đọc công khai không cần transaction.

### 13.4. Giới hạn request (`common/ratelimit/`, ADR-026 ở infra)

Token bucket: mỗi khoá (một IP, một người dùng) có một xô chứa tối đa `capacity` lượt, tự đầy lại `refillPerSecond`
lượt mỗi giây; mỗi request lấy một lượt, hết lượt thì 429. Cho phép dồn một lúc (mở trang gọi 5 API cùng lúc) nhưng
giới hạn trung bình. Toàn bộ phép tính nằm trong một script Lua (`RateLimiter.TOKEN_BUCKET`), giờ lấy bằng lệnh `TIME`
của Redis: nhiều instance cùng trừ một xô không đếm sai, giờ của từng máy lệch nhau cũng không sao.

`RateLimitInterceptor` là một `HandlerInterceptor`: chạy sau Spring Security (đã biết người gọi), trước controller.
Endpoint có `@RateLimited(ORDERS)` bị giới hạn thêm theo người dùng. IP thật lấy từ `X-Forwarded-For`; gateway nginx
**ghi đè** header này (không nối thêm), không thì bot đổi IP chỉ bằng cách tự gửi header khác.

### 13.5. Phòng chờ (`waitingroom/`, ADR-027)

Load test (13.7) cho thấy: cho 2.000 người đặt vé cùng một giây thì máy chủ không kịp. Phòng chờ biến "2.000 người
trong một giây" thành "200 người mỗi giây":

1. Vào hàng: script Lua `ZADD` người này vào sorted set `queue` (đã có thì giữ chỗ cũ). Đến trước giờ mở bán thì điểm
   là số ngẫu nhiên trong [0, 1), đến sau thì là số tăng dần: người đến sớm đứng trước và được xếp ngẫu nhiên, bấm F5
   lúc 19:59:59 không có lợi. Vị trí là `ZRANK + 1`.
2. Mỗi giây, job ở **mọi** instance chạy script `ADMIT`: `SET NX` khoá của giây đó (instance thứ hai dừng ngay), bỏ
   người đã quá 1 phút không hỏi thứ tự, `ZPOPMIN` N người vào tập `admitted`.
3. Người được vào nhận vé vào cửa `v1.<sự kiện>.<người>.<hạn>.<HMAC-SHA256>`. `POST /orders` kiểm tra chữ ký trong bộ
   nhớ (`AdmissionTokens.verify`, so sánh bằng `MessageDigest.isEqual` để không lộ chữ ký qua thời gian so sánh):
   không thêm lần gọi Redis nào vào lúc đông nhất, và Redis sập thì người đã được vào vẫn mua được.

Đọc `WaitingRoomIT.n_people_per_second_even_when_every_instance_runs_the_job` và
`people_arriving_before_the_sale_opens_are_shuffled_and_stay_ahead_of_later_arrivals`.

### 13.6. Redis sập thì sao (`common/redis/RedisBreaker`)

| Tính năng | Redis sập thì | Vì sao an toàn |
|---|---|---|
| Sơ đồ ghế trực tiếp | Web quay về tải lại 15 giây một lần | Ảnh chụp đọc từ database |
| Cache | Không ảnh hưởng | Cache nằm trong bộ nhớ từng instance |
| Giới hạn request | Tạm không giới hạn | Lớp bảo vệ thêm, không phải lớp quyết định |
| Phòng chờ | Không vào hàng được (503); người đã có vé vào cửa vẫn mua | Vé vào cửa kiểm tra bằng chữ ký |
| Lớp lọc ghế (bản thử) | Vào thẳng database | Database luôn quyết định (ADR-028) |

Cái bẫy: Redis không trả lời thì mỗi lệnh chờ hết timeout (0,5 giây). Mỗi request gọi Redis hai lần (giới hạn theo IP,
theo người dùng) thì **mọi request chậm thêm 1 giây**, tức là Redis sập kéo theo cả hệ thống. `RedisBreaker` là một
cầu dao nhỏ: lỗi một lần thì 5 giây sau không gọi Redis nữa, trả ngay giá trị dự phòng; hết 5 giây thử lại một lần.
`SeatMapLiveIT.selling_goes_on_while_redis_does_not_answer` tạm dừng container Redis (`docker pause`) để kiểm tra.

### 13.7. Hai instance và load test (`../infra/loadtest/`, ADR-029)

Trong compose, mọi instance core có thêm tên chung `core`; gateway gọi `core` và DNS của Docker trả địa chỉ của tất
cả. Instance nào cũng như nhau vì mọi trạng thái chung nằm ở PostgreSQL và Redis: job nền nhận việc bằng
`SKIP LOCKED`, job phòng chờ dùng khoá theo giây, thay đổi ghế đi qua pub/sub.

`scripts/loadtest.sh` chạy k6 (công cụ load test viết kịch bản bằng JavaScript) trong Docker: 2.000 người dùng ảo đăng
ký, đăng nhập, mở trang trước giờ mở bán, rồi cùng đặt 2 ghế lúc mở bán; sau đó `verify.sql` kiểm tra không ghế nào
bán trùng. Mỗi instance core bị giới hạn 2 CPU (như máy chủ thật), và kịch bản có một pha khởi động cho JVM "nóng".
Số đo đầy đủ ở ADR-029; các bài học:

- **Đo trước, đừng đoán chỗ nghẽn.** Giả thuyết ban đầu là database (khoá dòng trên mấy hàng ghế đầu). `docker stats`
  trong lúc đo cho thấy core dùng hết 2 CPU, PostgreSQL rảnh. Vì vậy lớp lọc Redis (ADR-028), dù nhanh gấp 3,5 lần khi
  đo riêng phần giữ ghế, không giúp gì cho cả hệ thống.
- **Một máy làm mọi việc thì số đo sai.** Không giới hạn CPU, k6 tự ăn 7 lõi và mọi container tranh nhau CPU; trang
  trúng cache cũng chậm 2 giây, kết luận nào cũng sai.
- **JVM mới khởi động chậm hơn nhiều** (JIT chưa biên dịch): đợt mở bán đánh vào JVM lạnh có nhiều 504 hơn hẳn.
- **Thêm instance không thay được kiểm soát lượng vào.** Hai instance hết lỗi 5xx nhưng p95 của đặt vé vẫn 1–6 giây;
  phòng chờ 100 người mỗi giây đưa p95 xuống 12 ms (người cuối hàng chờ khoảng 20 giây rồi đặt ngay).
- **Chính phòng chờ cũng có thể tạo tải**: 2.000 người hỏi thứ tự mỗi 2 giây, mỗi lần hỏi đọc database, là 1.000 câu
  SELECT mỗi giây. Cache sự kiện và hỏi thưa hơn khi còn xa lượt mới làm phòng chờ nhẹ đúng như mục đích.

### 13.8. Tóm tắt

| Vấn đề | Đừng | Làm |
|---|---|---|
| Thay đổi tới trình duyệt khác | Tải lại định kỳ; phát trong transaction | SSE, phát sau commit, gom theo lô, version từng bản ghi |
| Nhiều instance | Giữ trạng thái chung trong bộ nhớ một instance | PostgreSQL/Redis, pub/sub, khoá theo giây, `SKIP LOCKED` |
| Trang đông người đọc | `@Cacheable(sync)` hết hạn đồng loạt; cache cùng transaction | Cache trong bộ nhớ, làm mới ở nền |
| Bot, người bấm liên tục | Đếm trong bộ nhớ từng instance; tin `X-Forwarded-For` do client gửi | Token bucket bằng Lua trên Redis; gateway ghi đè header |
| Đông hơn sức chứa | Cho tất cả vào rồi hy vọng | Phòng chờ, vé vào cửa ký HMAC |
| Phụ thuộc thêm một hạ tầng | Để nó sập kéo theo cả hệ thống | Giá trị dự phòng và cầu dao, database vẫn quyết định |
| Hiệu năng | Đoán chỗ nghẽn | Load test có giới hạn CPU, JVM nóng, `docker stats` lúc đo |

---

## 14. M6: đối soát thanh toán, lịch sử thao tác

M6 là "vận hành": giữ hệ thống đúng khi có sự cố và để lại dấu vết. Hai phần đã làm:

### 14.1. Đối soát thanh toán (`payment/PaymentReconciliation*`, ADR-022)

Đơn chỉ thành PAID khi webhook đến. Webhook có thể không bao giờ đến (MockPay `dropWebhook`): khách đã trả tiền, đơn
hết hạn, ghế về tay người khác. Job đối soát chạy mỗi phút, hỏi cổng `GET /api/v1/payments/by-ref/{paymentId}` cho
payment chưa có kết quả sau 2 phút, hỏi lại thưa dần (2, 3, 5, 10, 30 phút, 1, 3, 6, 12 giờ, rồi mỗi ngày).

- **Nhận việc**: `UPDATE payments SET next_reconcile_at = now + 2 phút WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)
  RETURNING id`. Hai instance không nhận trùng; app sập giữa chừng thì 2 phút sau payment được nhận lại (lease).
- **Xử lý bằng đúng đường của webhook**: cổng báo đã trả thì dựng một event `payment.succeeded` và gọi
  `PaymentWebhookProcessor.process`. Cùng thứ tự khoá, cùng chống trùng, cùng chính sách đơn hết hạn thì hoàn tiền.
  Webhook thật đến cùng lúc thì một bên ghi nhận, bên kia nhận `DUPLICATE`.
- **Bẫy của Hibernate**: entity `Payment` có cột `next_reconcile_at` mà JDBC cũng ghi. Hibernate cập nhật cả dòng mỗi
  lần flush, nên ghi đè giá trị JDBC vừa đặt. `@Column(updatable = false)`: entity chỉ ghi cột này lúc tạo.

### 14.2. Lịch sử thao tác (`audit/`, ADR-023 ở infra)

Ai đổi giá vé, ai tải danh sách khách có email, hệ thống hoàn tiền đơn nào. Mỗi service một bảng `audit_logs`.

- `AuditLog.record` có `@Transactional(propagation = MANDATORY)`: bắt buộc chạy trong transaction của thay đổi, nên
  thay đổi rollback thì dòng lịch sử cũng mất. Tải file CSV không có transaction: `recordNow` (`REQUIRES_NEW`).
- Người làm lấy từ `SecurityContextHolder` (giống `AuditorAware` của Spring Data): JWT thì là người dùng, không có thì
  là `SYSTEM` kèm tên job.
- **Chỉ ghi thêm**: trigger `BEFORE UPDATE OR DELETE` ném lỗi. Bỏ dữ liệu cũ là `DROP` một bảng con theo tháng (như
  `checkin_logs`, dùng chung `MonthlyPartitions`).
- `details` không chứa email, token: lịch sử giữ lâu và nhiều người xem; trang quản trị tra email theo id lúc hiển thị.

---

## 15. Test

### 15.1. Hai loại test

| | Unit test (`*Test`) | Integration test (`*IT`) |
|---|---|---|
| Chạy gì | Một class, không Spring, không database | Toàn bộ app thật + Postgres + Kafka thật trong Docker |
| Tốc độ | Mili giây | Vài giây mỗi class (lần đầu chờ container khởi động) |
| Ví dụ | `QrTokenCodecTest`, `OrderStatusTest`, `VietnameseTextTest` | `SeatClaimConcurrencyIT`, `PaymentWebhookIT` |
| Lệnh | `./mvnw test` | `./mvnw verify` |

### 15.2. JUnit 5 và AssertJ

```java
class RequestHashTest {

    @Test                                         // đánh dấu một test
    void order_of_items_and_seats_does_not_matter() {
        String one = RequestHash.of(...);
        String two = RequestHash.of(...);

        assertThat(one).isEqualTo(two).hasSize(64);   // AssertJ: đọc gần như câu tiếng Anh
    }
}
```

- `@ParameterizedTest` chạy một test với nhiều bộ dữ liệu (`OrderStatusTest` chạy 25 lần, `VietnameseTextTest` 6 lần).
- `assertThatThrownBy(() -> ...).isInstanceOf(SoldOutException.class)` kiểm tra code ném đúng exception.
- Tên test viết như một câu mô tả hành vi: đọc danh sách tên test là biết hệ thống làm gì.

### 15.3. Hạ tầng test (`src/test/java/com/ticket2h/support/`)

| Class | Vai trò |
|---|---|
| `TestcontainersConfig` | Khai báo container Postgres và Kafka; `@ServiceConnection` tự nối app vào đó |
| `IntegrationTest` | Lớp cha mọi `*IT`: khởi động app ở cổng ngẫu nhiên, **xoá sạch bảng trước mỗi test** |
| `MutableClock` | Đồng hồ "tua" được: `clock.advance(Duration.ofMinutes(12))` |
| `TestData` | Tạo nhanh dữ liệu bằng SQL: `user()`, `eventOnSale(...)`, `seats(...)`, `paidOrderViaWebhook(...)` |
| `BulkData` | (M4) Hàng trăm nghìn đơn, vé, lượt quét sinh ngay trong PostgreSQL bằng `generate_series`, không qua bộ nhớ JVM |
| `Concurrency` | Chạy N việc trên M luồng, cố cho chúng bắt đầu cùng lúc |
| `FakeGateway` | Cổng thanh toán giả bằng HTTP server có sẵn của JDK; lập trình được để trả 500, chậm, 503 |
| `KafkaTestMessages` | Gửi tay và đọc message Kafka trong test (kiểm tra DLT, hợp đồng event) |
| `Api` | Gọi API thật qua HTTP, không ném exception với 4xx/5xx để test kiểm tra mã lỗi |

Test email nằm ở notification-service, có hạ tầng riêng: `FakeMailSender` ghi lại email thay cho mail server thật,
`FakeCoreApi` là HTTP server giả đóng vai core-service. Phía core, `TicketEventsContractIT` và `InternalApiIT` giữ
hợp đồng với notification-service ([infra/docs/contracts.md](https://github.com/ticket2h/infra/blob/main/docs/contracts.md)).

**Testcontainers** là thư viện bật container Docker ngay trong test. Nhờ nó, test chạy với PostgreSQL thật, nên
`FOR UPDATE`, ràng buộc, `ON CONFLICT` được kiểm tra thật, không phải giả lập.

**Vì sao `IntegrationTest` không có `@Transactional`?** Nhiều hướng dẫn gắn `@Transactional` lên test để tự rollback
dữ liệu sau mỗi test. Nhưng khi đó mọi thao tác chạy trong transaction của luồng test, các luồng khác **không thấy**
dữ liệu chưa commit, và test đồng thời trở nên vô nghĩa. Nên ở đây dữ liệu được dọn bằng `TRUNCATE` trước mỗi test.

### 15.4. Đọc một test đồng thời

`acceptance/SeatClaimConcurrencyIT.java`:

```java
@Test
void only_one_of_200_concurrent_buyers_gets_the_same_seat() throws Exception {
    var event = testData.eventOnSale(e -> {});                                  // Given: một sự kiện đang bán,
    UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
    UUID seatA12 = testData.seats(event, vip, 1).getFirst();                    //   một ghế,
    List<UUID> orders = testData.pendingOrders(event, 200);                     //   200 đơn của 200 người

    List<Boolean> results = Concurrency.run(200, 200, i -> {                    // When: 200 luồng cùng giữ ghế
        try {
            tx.executeWithoutResult(s -> seatHolds.hold(orders.get(i), event.id(),
                    List.of(seatA12), clock.instant(), clock.instant().plus(Duration.ofMinutes(10))));
            return true;
        } catch (SeatUnavailableException e) {
            return false;
        }
    });

    assertThat(results).filteredOn(ok -> ok).hasSize(1);                        // Then: đúng 1 người thành công
    assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatA12))
            .isEqualTo("HELD");                                                 //   và ghế ở trạng thái HELD
}
```

- Cấu trúc **Given / When / Then**: chuẩn bị, hành động, kiểm tra.
- `tx.executeWithoutResult(...)`: `TransactionTemplate` mở transaction bằng code (thay cho annotation), vì
  `SeatHoldService.hold` bắt buộc chạy trong transaction (`MANDATORY`).
- Chỉ bắt `SeatUnavailableException`. Lỗi khác (deadlock, lỗi SQL) sẽ lọt ra khỏi `Concurrency.run` và làm test đỏ.
  Đó đúng là điều ta muốn.

`Concurrency.run` dùng hai `CountDownLatch`: `ready` chờ đủ luồng sẵn sàng, `start` thả tất cả cùng lúc. Không có
nó, luồng đầu tiên có thể chạy xong trước khi luồng cuối kịp khởi động, và test không còn "đồng thời".

### 15.5. Test việc chạy bất đồng bộ

Phát vé đi qua Kafka nên không xảy ra ngay. Dùng **Awaitility** để chờ có giới hạn:

```java
await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(ticketCount(orderId)).isEqualTo(4));
```

Không dùng `Thread.sleep(10000)`: chờ cố định thì vừa chậm (luôn chờ đủ 10 giây) vừa dễ đỏ (máy chậm hơn thì 10
giây không đủ).

### 15.6. Package `acceptance`

Các test quan trọng nhất (đồng thời, webhook, QR, check-in, chuyển nhượng, app sập, và bốn tiêu chí M4 ở mục 12) nằm
trong `src/test/java/com/ticket2h/acceptance/`. Quy tắc trong `CLAUDE.md`: **test đỏ thì sửa code, không sửa test**.
Một agent AI (hoặc chính bạn khi vội) rất dễ "sửa" test cho xanh thay vì sửa lỗi thật.

### 15.7. Chạy và debug test trong IntelliJ

- Bấm ▶ cạnh tên class hoặc tên method test.
- Đặt breakpoint trong code app (ví dụ trong `SeatHoldService.hold`), bấm 🐞 cạnh test: app dừng đúng chỗ đó
  trong lúc test chạy. Với test đồng thời, IntelliJ dừng từng luồng; bảng *Threads* trong cửa sổ Debug cho bạn
  chuyển qua lại giữa các luồng.

---

## 16. Bài tập thực hành và câu hỏi tự kiểm tra

**Trước khi làm bài tập cấp 2 trở lên:** commit code hiện tại (`git add . && git commit -m "M1-M3"`) để luôn quay lại
được bằng `git restore <file>` hoặc `git stash`.

### Cấp 1: quan sát (không sửa code)

1. **Debug một request.** Đặt breakpoint ở dòng đầu `OrderCreationService.create`, chạy app bằng 🐞, chạy
   `scripts/demo.sh`. Bước qua từng dòng (F8). Khi đang dừng sau `orders.saveAndFlush(order)`, mở DBeaver và tìm
   đơn mới trong bảng `orders`. **Bạn sẽ không thấy nó. Vì sao?** (Gợi ý: transaction chưa commit; mức cô lập
   READ COMMITTED.)
2. **Đọc lịch sử migration.** Trong DBeaver, mở bảng `flyway_schema_history`. Mỗi dòng ứng với file nào?
3. **Webhook gửi trùng.** Chạy demo, rồi xem `payment_webhook_logs`: có mấy dòng, cột `result` là gì? Đối chiếu với
   `WebhookOutcome`.
4. **Giải mã JWT.** Lấy `accessToken` từ `POST /auth/login`, cắt phần giữa (giữa hai dấu chấm), chạy
   `echo '<phần giữa>' | base64 -d`. Bạn đọc được gì? Vì sao không sửa được nó?
5. **Kiểm tra ràng buộc.** Trong DBeaver chạy
   `UPDATE ticket_types SET available_quantity = -1 WHERE kind = 'GA';`. Đọc thông báo lỗi, tìm ràng buộc đó trong
   file migration nào.

### Cấp 2: sửa nhỏ, xem test phản ứng

6. **Đổi thời gian giữ chỗ.** Sửa `hold-duration: PT10M` thành `PT5M` trong `application.yml`, chạy `OrderExpiryIT`
   và `OrderPlacementIT`. Test nào đỏ, vì sao? Trả lại như cũ.
7. **Thêm cột mới đúng cách.** Thêm số điện thoại cho tài khoản: tạo `V113__users_phone.sql`
   (`ALTER TABLE users ADD COLUMN phone varchar(20);`), thêm trường vào `User`, `RegisterRequest`, `UserResponse`,
   có validation `@Pattern`. Vì sao **không** được sửa `V1__m1_identity.sql`?
8. **Thêm cách gom báo cáo theo tuần.** Đọc `reporting/SalesReportStore`. Thêm `WEEK` vào `SalesGroupBy` (tuần bắt đầu
   thứ Hai: `date_trunc('week', sale_date)::date`), thêm nhánh SQL tương ứng, không đổi bảng tổng hợp. Viết một test
   trong `SalesReportApiIT`: hai đơn trả tiền thứ Hai và Chủ nhật cùng tuần ra một dòng. Vì sao không cần sửa job cộng
   dồn?

### Cấp 3: tự tạo lỗi, xem test bắt được

Mỗi bài: sửa, chạy test, quan sát, rồi **hoàn tác**.

9. Xoá `ORDER BY id` trong `SeatHoldService.hold`. Chạy `SeatClaimConcurrencyIT`. Bạn sẽ thấy điều bất ngờ: test
   `overlapping_multi_seat_holds_never_deadlock` ({A,B} và {B,A}) **vẫn xanh**, còn
   `releasing_expired_seats_while_someone_retakes_them_never_deadlocks` đỏ với `deadlock detected`. Vì sao?
   (Gợi ý: với `WHERE id IN (...)`, PostgreSQL *tình cờ* trả dòng theo thứ tự của index, bất kể bạn truyền danh
   sách theo thứ tự nào. Nhưng `release()` khoá theo `ORDER BY id`, còn `hold()` giờ khoá theo thứ tự PostgreSQL tự
   chọn. SQL không hứa thứ tự nào nếu không có `ORDER BY`.)
10. Xoá `FOR UPDATE` trong cùng câu đó. Chạy lại. Vì sao lỗi lại là `IllegalStateException` mà không phải bán trùng?
    (Gợi ý: câu `UPDATE` phía sau vẫn lặp lại điều kiện.)
11. Đổi `orders.saveAndFlush(order)` thành `orders.save(order)` trong `OrderCreationService`. Chạy
    `OrderPlacementIT`. Lỗi là vi phạm khoá ngoại `seats_order_id_fkey`. Vì sao? (Gợi ý: với `save`, câu INSERT đơn
    chưa chạy lúc `SeatHoldService` ghi `order_id` vào ghế.) Ràng buộc database đã bắt lỗi trước cả logic
    idempotency ở mục 9.3: đúng vai trò "lớp chặn cuối cùng" ở mục 4.3.
12. Xoá `@Transactional` trên `OrderCreationService.create`. Chạy `OrderPlacementIT`. Lỗi
    `IllegalTransactionStateException` đến từ đâu? (Gợi ý: `Propagation.MANDATORY`.)
13. Xoá `ON CONFLICT (order_item_id, item_seq) DO NOTHING` trong `TicketStore.insertTickets`. Chạy `TicketIssuanceIT`.
    Có test nào đỏ không? Nếu không, vì sao dòng đó vẫn đáng giữ? (Gợi ý: phòng thủ nhiều lớp.)
14. Đổi `MessageDigest.isEqual` thành `Arrays.equals` trong `WebhookSignatureVerifier`. Test vẫn xanh. Đây là loại
    lỗi nào mà test chức năng không bắt được?
14a. (M4) Trong `AttendeeExport.export`, gom mọi dòng vào một `List<String>` rồi mới ghi ra `csv`. Chạy riêng
    execution 256MB: `./mvnw test-compile failsafe:integration-test@export-memory failsafe:verify@export-memory`.
    Lỗi gì? Thử tiếp: bỏ `@Transactional(readOnly = true)` trên `CheckinLogReader.read`.
    Test lịch sử soát vé còn xanh không, vì sao? (Gợi ý: fetch size và autocommit, mục 12.5.)
14b. (M4) Xoá `id DESC` khỏi `ORDER BY` và khỏi điều kiện keyset trong `OrderListStore.page` (chỉ còn `created_at`).
    Test nào đỏ? (Gợi ý: đơn tạo cùng micro giây.)

### Cấp 4: tự viết tính năng

15. **BTC huỷ vé.** `POST /organizer/tickets/{id}/void` chuyển vé `VALID` → `VOID` bằng một câu UPDATE có điều kiện;
    vé của sự kiện BTC khác → 403. Viết test: quét QR của vé đã huỷ phải trả `VOIDED`.
16a. **(M4) Hoàn tiền sau khi đã PAID.** Giả sử ban tổ chức huỷ sự kiện và hoàn tiền mọi đơn. Báo cáo doanh thu phải
    trừ đi. Thiết kế lại hàng đợi `sales_rollup_queue` (gợi ý: thêm dấu +/−, hoặc một bảng hàng đợi riêng) sao cho mỗi
    lần hoàn vẫn được trừ đúng một lần. Viết ADR ngắn trước khi code.
16. **Giới hạn chuyển nhượng.** Mỗi vé chỉ chuyển được một lần (ADR-004 nói hạn mức có thể bị lách qua chuyển
    nhượng). Làm trong **một** câu UPDATE, không đọc rồi kiểm tra. Viết test 2 luồng cùng chuyển một vé.

### Câu hỏi tự kiểm tra

Trả lời không nhìn code. Nhiều câu là câu phỏng vấn backend phổ biến.

**Spring**
1. Bean là gì? Ai tạo ra nó? Vì sao project tiêm phụ thuộc qua constructor?
2. `@Service` khác `@Component` thế nào? `@Bean` dùng khi nào?
3. Vì sao gọi một method `@Transactional` từ chính class đó thì không có transaction?
4. Exception nào làm Spring rollback, exception nào không?
5. `MANDATORY` và `REQUIRES_NEW` dùng ở đâu trong project, để làm gì?
6. Vì sao trả 404 chứ không phải 403 khi xem đơn của người khác?

**Đồng thời và dữ liệu**

7. Vì sao `UPDATE ... SET available = available - 1 WHERE available >= 1` an toàn mà không cần `SELECT ... FOR UPDATE`?
8. Hai người giữ `{A, B}` và `{B, A}` cùng lúc. Chuyện gì xảy ra nếu không `ORDER BY id`?
9. Vì sao INSERT đơn hàng phải là thao tác ghi đầu tiên để idempotency key hoạt động với request song song?
10. "Hết hạn lười" là gì? Nó gây ra ca khó nào khi webhook đến trễ, và ta xử lý ra sao?
11. Vì sao job dọn đơn xử lý mỗi đơn trong một transaction riêng?

**Thanh toán**

12. Webhook gửi 3 lần song song. Dòng code hoặc ràng buộc nào khiến chỉ một lần có tác dụng?
13. Vì sao không được tin tham số trên `returnUrl`?
14. Vì sao tính chữ ký trên body thô, và so sánh bằng `MessageDigest.isEqual`?
15. Vì sao gọi API hoàn tiền ngoài transaction? Nếu app sập ngay sau khi gọi thì sao?

**Vé**

16. Hai cách viết sai khi vừa cập nhật database vừa gửi Kafka là gì? Outbox giải quyết thế nào?
17. Outbox bảo đảm "ít nhất một lần" hay "đúng một lần"? Hệ quả với consumer là gì?
18. Vì sao QR dùng Ed25519 mà không dùng HMAC?
19. Chữ ký QR có chống được chụp màn hình rồi chia sẻ không? Cái gì chống?
20. Check-in và chuyển nhượng cùng lúc trên một vé. Chứng minh chỉ một bên thành công.

---

## 17. Thuật ngữ

| Thuật ngữ | Nghĩa ngắn gọn |
|---|---|
| Bean | Object do Spring tạo và quản lý |
| IoC / DI | Spring tạo object và tự truyền phụ thuộc vào, thay vì bạn `new` |
| ApplicationContext | "Kho" chứa mọi bean của app |
| Annotation | Nhãn `@...` gắn lên class/method để Spring biết cách xử lý |
| Auto-configuration | Spring Boot tự tạo bean dựa trên thư viện có mặt và cấu hình |
| Profile | Chế độ chạy (`dev`, `test`) bật/tắt bean và cấu hình |
| Proxy | Object bọc ngoài bean, chen logic vào (mở transaction, kiểm tra quyền) |
| Controller | Lớp nhận HTTP request |
| DTO | Object chỉ để chở dữ liệu vào/ra API |
| Entity | Class ánh xạ một bảng (JPA) |
| ORM / JPA / Hibernate | Ánh xạ bảng ↔ object / chuẩn Java cho việc đó / thư viện cài đặt chuẩn |
| Repository | Interface truy cập dữ liệu; Spring Data tự sinh code |
| Migration (Flyway) | File SQL thay đổi cấu trúc database, chạy theo thứ tự, không sửa lại |
| Constraint | Ràng buộc trong database (CHECK, UNIQUE, FOREIGN KEY) |
| Transaction | Nhóm thao tác cùng commit hoặc cùng rollback |
| Commit / Rollback | Lưu hẳn / huỷ toàn bộ thay đổi của transaction |
| Isolation (READ COMMITTED) | Transaction chỉ thấy dữ liệu đã commit của transaction khác |
| Race condition | Kết quả sai do nhiều luồng chạy xen kẽ |
| Check-then-act | Kiểm tra rồi mới làm, có khe hở giữa hai bước |
| Lock / `FOR UPDATE` | Khoá dòng, transaction khác phải chờ |
| `SKIP LOCKED` | Bỏ qua dòng đang bị khoá thay vì chờ |
| Deadlock | Hai bên chờ nhau mãi; DB huỷ một bên |
| Idempotent | Làm nhiều lần cho cùng kết quả như làm một lần |
| Idempotency key | Mã client gửi kèm để server nhận ra request lặp lại |
| Webhook | Hệ thống khác gọi vào API của bạn để báo tin |
| HMAC | Chữ ký dùng chung một bí mật giữa hai bên |
| Chữ ký bất đối xứng (Ed25519) | Ký bằng khoá riêng, kiểm tra bằng khoá công khai |
| JWT | Token đăng nhập có chữ ký, chứa userId và vai trò |
| Stateless | Server không lưu phiên đăng nhập; mỗi request tự mang token |
| Outbox | Ghi event vào bảng cùng transaction, gửi đi sau |
| At-least-once | Bảo đảm gửi ít nhất một lần, có thể trùng |
| Kafka topic / partition / consumer group | Kênh message / phần chia của kênh / nhóm cùng đọc |
| DLT | Topic chứa message xử lý mãi không được |
| Lease | "Thuê" một việc trong một khoảng thời gian, hết hạn thì người khác làm lại |
| Backoff | Thử lại với thời gian chờ tăng dần |
| Testcontainers | Thư viện bật container Docker (Postgres, Kafka) trong test |
| Given / When / Then | Cấu trúc test: chuẩn bị, hành động, kiểm tra |
| `EXPLAIN ANALYZE` | Chạy câu SQL và in kế hoạch thực thi kèm thời gian, số dòng thật của từng bước |
| Seq Scan / Index Scan | Đọc cả bảng / đi theo index |
| Bảng tổng hợp (rollup) | Bảng giữ sẵn kết quả `SUM`/`COUNT` đã gom, cập nhật dần thay vì tính lại mỗi lần |
| Materialized view | View lưu sẵn kết quả, phải `REFRESH` để cập nhật |
| Keyset pagination | Phân trang bằng "đứng sau dòng cuối đã thấy" thay vì `OFFSET` |
| Cursor (phân trang) | Chuỗi mờ đánh dấu vị trí trong danh sách, client gửi lại để lấy trang sau |
| Fetch size | Số dòng driver lấy về mỗi lần khi đọc theo luồng (PostgreSQL: chỉ khi autocommit tắt) |
| `COPY` | Lệnh nạp dữ liệu hàng loạt của PostgreSQL, nhanh hơn INSERT nhiều lần |
| JDBC batch | Gửi nhiều câu lệnh cùng loại trong một lượt đi về database |
| Partition / partition pruning | Chia một bảng thành nhiều bảng con / database bỏ qua bảng con không liên quan |
| CSV injection | Ô CSV bắt đầu bằng `=`, `+`, `-`, `@` bị Excel chạy như công thức |
