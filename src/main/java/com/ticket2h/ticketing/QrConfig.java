package com.ticket2h.ticketing;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ticket2h.common.config.Ticket2hProperties;

/** Nạp khoá Ed25519 từ cấu hình (base64 của PKCS#8 / X.509 DER). Sai khoá thì app không khởi động. */
@Configuration(proxyBeanMethods = false)
class QrConfig {

    @Bean
    QrTokenCodec qrTokenCodec(Ticket2hProperties properties) throws GeneralSecurityException {
        Ticket2hProperties.Qr qr = properties.qr();
        KeyFactory keys = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = keys.generatePrivate(new PKCS8EncodedKeySpec(decode(qr.privateKey(), "private-key")));
        Map<String, PublicKey> publicKeys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : qr.publicKeys().entrySet()) {
            publicKeys.put(entry.getKey(),
                    keys.generatePublic(new X509EncodedKeySpec(decode(entry.getValue(), "public-keys." + entry.getKey()))));
        }
        return new QrTokenCodec(qr.activeKeyId(), privateKey, publicKeys);
    }

    private static byte[] decode(String base64, String property) {
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ticket2h.qr." + property + " không phải base64 hợp lệ", e);
        }
    }
}
