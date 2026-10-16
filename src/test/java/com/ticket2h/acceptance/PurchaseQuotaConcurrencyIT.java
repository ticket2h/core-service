package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticket2h.inventory.PurchaseLimitExceededException;
import com.ticket2h.inventory.PurchaseQuotas;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;

/** Bước 5: hạn mức vé mỗi user. */
class PurchaseQuotaConcurrencyIT extends IntegrationTest {

    @Autowired PurchaseQuotas quotas;
    @Autowired TransactionTemplate tx;

    @Test
    void parallel_tabs_cannot_exceed_purchase_limit() throws Exception {
        var event = testData.eventOnSale(e -> e.maxTicketsPerUser(4));
        UUID buyer = testData.user();

        List<Boolean> results = Concurrency.run(10, 10, i -> {
            try {
                tx.executeWithoutResult(s -> quotas.reserve(buyer, event.id(), 2, 4));
                return true;
            } catch (PurchaseLimitExceededException e) {
                return false;
            }
        });

        assertThat(results).filteredOn(ok -> ok).hasSize(2);
        assertThat(reserved(buyer, event.id())).isEqualTo(4);
    }

    @Test
    void asking_for_more_than_the_limit_at_once_is_rejected_without_creating_a_row() {
        var event = testData.eventOnSale(e -> e.maxTicketsPerUser(4));
        UUID buyer = testData.user();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quotas.reserve(buyer, event.id(), 5, 4)))
                .isInstanceOf(PurchaseLimitExceededException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_quotas WHERE user_id = ?", Integer.class, buyer))
                .isZero();
    }

    @Test
    void released_tickets_can_be_bought_again() {
        var event = testData.eventOnSale(e -> e.maxTicketsPerUser(4));
        UUID buyer = testData.user();
        tx.executeWithoutResult(s -> quotas.reserve(buyer, event.id(), 4, 4));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quotas.reserve(buyer, event.id(), 1, 4)))
                .isInstanceOf(PurchaseLimitExceededException.class);

        tx.executeWithoutResult(s -> quotas.release(buyer, event.id(), 2));
        tx.executeWithoutResult(s -> quotas.reserve(buyer, event.id(), 2, 4));

        assertThat(reserved(buyer, event.id())).isEqualTo(4);
    }

    private int reserved(UUID buyer, UUID eventId) {
        return jdbc.queryForObject("SELECT reserved FROM purchase_quotas WHERE user_id = ? AND event_id = ?",
                Integer.class, buyer, eventId);
    }
}
