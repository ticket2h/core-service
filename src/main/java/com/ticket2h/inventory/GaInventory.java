package com.ticket2h.inventory;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bộ đếm vé GA trên {@code ticket_types.available_quantity}.
 *
 * <p>Không cần {@code SELECT ... FOR UPDATE}: câu UPDATE tự khoá dòng. Ở READ COMMITTED, request
 * phải chờ sẽ được PostgreSQL đánh giá lại điều kiện WHERE trên phiên bản mới nhất của dòng
 * (EvalPlanQual), nên khi vé cuối đã bị lấy thì {@code available_quantity >= :quantity} thành sai.
 */
@Service
public class GaInventory {

    private final NamedParameterJdbcTemplate jdbc;

    public GaInventory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws SoldOutException không đủ {@code quantity} vé */
    @Transactional(propagation = Propagation.MANDATORY)
    public void take(UUID eventId, UUID ticketTypeId, int quantity) {
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
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID ticketTypeId, int quantity) {
        int updated = jdbc.update("""
                UPDATE ticket_types
                SET available_quantity = available_quantity + :quantity
                WHERE id = :ticketTypeId AND kind = 'GA'
                """, Map.of("quantity", quantity, "ticketTypeId", ticketTypeId));
        if (updated != 1) {
            throw new IllegalStateException("Không trả lại được vé GA cho loại vé " + ticketTypeId);
        }
    }
}
