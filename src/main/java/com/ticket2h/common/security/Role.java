package com.ticket2h.common.security;

/** Vai trò người dùng. Mỗi tài khoản có đúng một vai trò. */
public enum Role {
    CUSTOMER, ORGANIZER, STAFF, ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
