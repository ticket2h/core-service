package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ticket2h.common.events.Topics;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.outbox.OutboxBatchPublisher;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.KafkaTestMessages;
import com.ticket2h.ticketing.FlakyIssuanceConfig;
import com.ticket2h.ticketing.FlakyIssuanceConfig.FlakyTicketIssuance;
import com.ticket2h.ticketing.PaidOrderSweeper;

/**
 * Bước 20.1. Relay tắt: test tự gọi publishBatch để mô phỏng "app khởi động lại". Context riêng
 * (cấu hình khác), nên có Postgres và Kafka riêng.
 */
@TestPropertySource(properties = "ticket2h.outbox.relay.enabled=false")
@Import(FlakyIssuanceConfig.class)
class CrashRecoveryIT extends IntegrationTest {

    @Autowired OutboxBatchPublisher publisher;
    @Autowired FlakyTicketIssuance issuance;
    @Autowired PaidOrderSweeper sweeper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaConnection;

    @BeforeEach
    void resetFlaky() {
        issuance.reset();
    }

    @Test
    void tickets_are_issued_after_restart_even_if_app_died_before_publishing() {
        UUID orderId = paidGaOrder();

        // "App chết": đơn đã PAID, outbox chưa gửi, chưa có vé
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class)).isEqualTo(1);
        assertThat(ticketCount(orderId)).isZero();

        // "App khởi động lại": relay chạy
        publisher.publishBatch(100);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(ticketCount(orderId)).isEqualTo(2));
    }

    @Test
    void consumer_failing_once_is_retried_and_still_issues_the_right_number_of_tickets() {
        issuance.failNext(1);
        UUID orderId = paidGaOrder();

        publisher.publishBatch(100);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(ticketCount(orderId)).isEqualTo(2));
        assertThat(issuance.calls()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void malformed_message_goes_to_dlt_and_later_messages_are_still_processed() {
        KafkaTestMessages.send(kafka, Topics.ORDER_EVENTS, "bad", Topics.ORDER_PAID, "đây không phải JSON");

        ConsumerRecord<String, String> dead = KafkaTestMessages.consume(kafkaConnection,
                Topics.ORDER_EVENTS + ".DLT", 1, Duration.ofSeconds(20)).getFirst();
        assertThat(dead.value()).isEqualTo("đây không phải JSON");

        UUID orderId = paidGaOrder();
        publisher.publishBatch(100);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(ticketCount(orderId)).isEqualTo(2));
    }

    /** Relay tắt nên chỉ có đúng hai luồng này phát vé, không đua với consumer nền. */
    @Test
    void two_issuers_running_in_parallel_for_the_same_order_still_give_two_tickets() throws Exception {
        UUID orderId = paidGaOrder();

        Concurrency.run(2, 2, i -> {
            issuance.issueFor(orderId);
            return null;
        });

        assertThat(ticketCount(orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("FULFILLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'TicketsIssued'",
                Integer.class)).isEqualTo(1);
    }

    /** Message OrderPaid mất hẳn (vào DLT, bị xoá...): job quét đơn PAID quá lâu vẫn phát vé. */
    @Test
    void sweeper_issues_tickets_for_orders_paid_long_ago_without_tickets() {
        UUID orderId = paidGaOrder();
        jdbc.update("DELETE FROM outbox_events");   // giả lập message không bao giờ tới consumer

        assertThat(sweeper.sweep()).isZero();       // chưa quá thời gian chờ
        clock.advance(Duration.ofMinutes(6));
        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(ticketCount(orderId)).isEqualTo(2);
        assertThat(sweeper.sweep()).isZero();       // đã FULFILLED
    }

    private UUID paidGaOrder() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        return testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.ga(event.id(), ga, 2));
    }

    private int ticketCount(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM tickets WHERE order_id = ?", Integer.class, orderId);
    }
}
