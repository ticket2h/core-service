package com.ticket2h.common.security;

public final class JwtClaims {

    /** Claim chứa vai trò, ví dụ {@code "role": "CUSTOMER"}. */
    public static final String ROLE = "role";

    /** Claim chứa id ban tổ chức, chỉ có ở token của tài khoản ORGANIZER. */
    public static final String ORGANIZER_ID = "organizerId";

    private JwtClaims() {}
}
