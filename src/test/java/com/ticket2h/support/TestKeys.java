package com.ticket2h.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Khoá sinh lúc chạy test: Ed25519 cho QR, RSA cho JWT (đóng vai khoá của identity-service). Không commit khoá nào. */
public final class TestKeys {

    public static final KeyPair QR_K1 = generate();

    /** Khoá ký JWT. Public key được {@link FakeIdentity} công bố qua JWKS. */
    public static final KeyPair JWT = rsa();

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String privateBase64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }

    public static String publicBase64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private TestKeys() {}
}
