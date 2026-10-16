package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticket2h.inventory.GaInventory;
import com.ticket2h.inventory.SoldOutException;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;

/** Bước 4: bộ đếm vé GA. */
class GaInventoryConcurrencyIT extends IntegrationTest {

    @Autowired GaInventory gaInventory;
    @Autowired TransactionTemplate tx;

    @Test
    @Tag("slow")
    void ten_thousand_requests_for_one_thousand_ga_tickets_sell_exactly_one_thousand() throws Exception {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 1_000);

        List<Boolean> results = Concurrency.run(10_000, 64, i -> {
            try {
                tx.executeWithoutResult(s -> gaInventory.take(event.id(), ga, 1));
                return true;
            } catch (SoldOutException e) {
                return false;
            }
        });

        assertThat(results).filteredOn(ok -> ok).hasSize(1_000);
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ga)).isZero();
    }

    @Test
    void taking_more_than_what_is_left_is_rejected_and_leaves_stock_untouched() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 3);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> gaInventory.take(event.id(), ga, 5)))
                .isInstanceOf(SoldOutException.class);
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ga)).isEqualTo(3);
    }

    @Test
    void ga_type_of_another_event_cannot_be_taken() {
        var event = testData.eventOnSale(e -> {});
        var other = testData.eventOnSale(e -> {});
        UUID otherGa = testData.gaTicketType(other, new BigDecimal("800000"), 10);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> gaInventory.take(event.id(), otherGa, 1)))
                .isInstanceOf(SoldOutException.class);
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, otherGa)).isEqualTo(10);
    }

    @Test
    void database_rejects_negative_stock_even_if_code_is_wrong() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 3);

        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_types SET available_quantity = -1 WHERE id = ?", ga))
                .hasMessageContaining("ticket_types_quantity_check");
    }
}
