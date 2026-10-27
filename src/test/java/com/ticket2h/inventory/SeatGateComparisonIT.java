package com.ticket2h.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

/**
 * ADR-028: giữ ghế chỉ bằng database so với có Redis lọc trước. 2.000 người cùng lúc tranh 100 ghế (mỗi người 2 ghế
 * liền nhau, cùng một kiểu chọn ghế cho cả hai lần đo). In bảng số đo ra log; khẳng định cả hai đều đúng (không ghế
 * nào hai người giữ) và Redis chặn phần lớn người thua trước khi họ chạm database.
 * <p>
 * {@code testData.placeOrder} đi qua {@code OrderPlacement} với lớp lọc của profile test ({@code database}, không lọc);
 * lần đo Redis bọc thêm {@link RedisSeatGate} ở ngoài, đúng như {@code OrderPlacement} làm khi {@code seat-gate=redis}.
 */
class SeatGateComparisonIT extends IntegrationTest {

    private static final int SEATS = 100;
    private static final int BUYERS = 2_000;
    private static final int THREADS = 40;
    private static final int WARM_UP_BUYERS = 500;
    private static final Logger log = LoggerFactory.getLogger(SeatGateComparisonIT.class);

    @Autowired private RedisSeatGate redisGate;

    @Test
    void redis_turns_losers_away_before_they_wait_for_database_locks() throws Exception {
        // Chạy thử cả hai một lần cho JIT và pool kết nối "nóng", không thì lần đo đầu tiên luôn chậm hơn
        measure("warm-up", SeatGate.DATABASE_ONLY, WARM_UP_BUYERS);
        measure("warm-up", redisGate, WARM_UP_BUYERS);
        Result database = measure("database", SeatGate.DATABASE_ONLY, BUYERS);
        Result redis = measure("redis", redisGate, BUYERS);

        log.info("""

                Giữ ghế: {} người, {} ghế, mỗi người 2 ghế liền nhau, {} luồng
                {}
                {}
                {}""", BUYERS, SEATS, THREADS, Result.HEADER, database, redis);
        for (Result result : List.of(database, redis)) {
            assertThat(result.held()).as(result.name()).isLessThanOrEqualTo(SEATS / 2);
            assertThat(result.heldSeats()).as(result.name()).isEqualTo(result.held() * 2L);
        }
        assertThat(redis.reachedDatabase()).as("Redis chặn người thua trước database")
                .isLessThan(database.reachedDatabase() / 4);
    }

    private Result measure(String name, SeatGate gate, int buyerCount) throws Exception {
        EventRef event = testData.eventOnSale(e -> e.maxTicketsPerUser(2));
        UUID type = testData.seatedTicketType(event, new BigDecimal("1000000"));
        List<UUID> seats = testData.seats(event, type, SEATS);
        List<UUID> buyers = testData.users(buyerCount);
        int[] starts = new Random(42).ints(buyerCount, 0, SEATS - 1).toArray();
        AtomicInteger reachedDatabase = new AtomicInteger();
        AtomicInteger held = new AtomicInteger();
        long[] latencies = new long[buyerCount];

        long started = System.nanoTime();
        Concurrency.run(buyerCount, THREADS, i -> {
            List<UUID> wanted = List.of(seats.get(starts[i]), seats.get(starts[i] + 1));
            UUID buyer = buyers.get(i);
            String key = UUID.randomUUID().toString();
            long t0 = System.nanoTime();
            try {
                gate.guard(event.id(), buyer + ":" + key, wanted, () -> {
                    reachedDatabase.incrementAndGet();
                    return testData.placeOrder(buyer, CreateOrderRequest.seated(event.id(), type, wanted));
                });
                held.incrementAndGet();
            } catch (SeatUnavailableException e) {
                // thua: ghế đã có người
            }
            latencies[i] = System.nanoTime() - t0;
            return null;
        });
        Duration total = Duration.ofNanos(System.nanoTime() - started);
        long heldSeats = jdbc.queryForObject("SELECT count(*) FROM seats WHERE event_id = ? AND status = 'HELD'",
                Long.class, event.id());
        Arrays.sort(latencies);
        return new Result(name, total, reachedDatabase.get(), held.get(), heldSeats,
                millis(latencies, 0.50), millis(latencies, 0.95), millis(latencies, 0.99));
    }

    private static double millis(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(percentile * sorted.length) - 1] / 1_000_000.0;
    }

    record Result(String name, Duration total, int reachedDatabase, int held, long heldSeats, double p50, double p95,
            double p99) {

        static final String HEADER = String.format("%-9s %10s %14s %9s %9s %9s %9s", "lớp lọc", "tổng (ms)",
                "tới database", "giữ được", "p50 (ms)", "p95 (ms)", "p99 (ms)");

        @Override
        public String toString() {
            return String.format("%-9s %10d %14d %9d %9.1f %9.1f %9.1f", name, total.toMillis(), reachedDatabase,
                    held, p50, p95, p99);
        }
    }
}
