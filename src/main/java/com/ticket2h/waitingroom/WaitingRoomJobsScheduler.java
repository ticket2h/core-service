package com.ticket2h.waitingroom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Chỉ chạy khi {@code ticket2h.jobs.enabled=true}. Test gọi {@link WaitingRoom#admitDue()} trực tiếp. */
@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")
class WaitingRoomJobsScheduler {

    private final WaitingRoom waitingRoom;

    WaitingRoomJobsScheduler(WaitingRoom waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    /** Mỗi giây ở mọi instance; Redis bảo đảm mỗi giây chỉ một instance cho người vào. */
    @Scheduled(fixedRate = 1_000, initialDelay = 5_000)
    void admit() {
        waitingRoom.admitDue();
    }
}
