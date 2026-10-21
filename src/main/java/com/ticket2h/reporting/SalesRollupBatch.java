package com.ticket2h.reporting;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Một lô của job cộng dồn trong một transaction riêng. Tách bean để {@code @Transactional} có hiệu lực. */
@Component
class SalesRollupBatch {

    private final SalesRollupStore store;

    SalesRollupBatch(SalesRollupStore store) {
        this.store = store;
    }

    @Transactional
    int apply(int limit) {
        store.lock();
        return store.applyBatch(limit);
    }
}
