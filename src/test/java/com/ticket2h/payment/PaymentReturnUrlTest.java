package com.ticket2h.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** return-url gửi MockPay: URL đã cấu hình kèm {@code orderId}, giữ nguyên query có sẵn. */
class PaymentReturnUrlTest {

    private static final UUID ORDER = UUID.fromString("0c000000-0000-4000-8000-000000000001");

    @Test
    void appends_order_id() {
        assertThat(PaymentInitiation.returnUrlFor("http://localhost:5173/payments/return", ORDER))
                .isEqualTo("http://localhost:5173/payments/return?orderId=" + ORDER);
    }

    @Test
    void keeps_query_already_in_the_configured_url() {
        assertThat(PaymentInitiation.returnUrlFor("https://shop.example/return?lang=vi&from=mail%20link", ORDER))
                .isEqualTo("https://shop.example/return?lang=vi&from=mail%20link&orderId=" + ORDER);
    }
}
