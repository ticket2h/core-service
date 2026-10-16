package com.ticket2h.common.persistence;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;

/** Nhận diện lỗi vi phạm ràng buộc theo tên. Đây là lý do mọi constraint đều được đặt tên. */
public final class Constraints {

    private static final String UNIQUE_VIOLATION = "23505";

    /** {@code true} nếu {@code error} (hoặc nguyên nhân của nó) là vi phạm unique {@code constraintName}. */
    public static boolean isUniqueViolation(Throwable error, String constraintName) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException hibernate
                    && constraintName.equalsIgnoreCase(hibernate.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("\"" + constraintName + "\"")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private Constraints() {}
}
