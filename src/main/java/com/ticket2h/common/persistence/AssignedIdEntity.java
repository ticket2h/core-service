package com.ticket2h.common.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Entity có id UUID do mình gán. Không có lớp này, {@code save()} thấy id khác null sẽ coi là
 * entity cũ và gọi {@code merge} (SELECT trước rồi mới INSERT), thay vì INSERT ngay khi flush.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id")
    private UUID id;

    @Transient
    private boolean isNew = true;

    protected AssignedIdEntity() {}

    protected AssignedIdEntity(UUID id) {
        this.id = id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
