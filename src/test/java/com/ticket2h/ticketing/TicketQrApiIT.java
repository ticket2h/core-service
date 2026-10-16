package com.ticket2h.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestKeys;

/** Bước 16.4: ảnh QR, khoá công khai. */
class TicketQrApiIT extends IntegrationTest {

    @Test
    void owner_gets_a_png_that_decodes_to_a_valid_token_and_is_not_cached() throws Exception {
        Fixture f = fixture();

        ResponseEntity<byte[]> response = api.getBytes("/tickets/" + f.ticket + "/qr", testData.token(f.buyer));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).hasToString("image/png");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(image)))).getText();
        assertThat(decoded).isEqualTo(testData.qrToken(f.ticket));
    }

    @Test
    void someone_else_gets_404() {
        Fixture f = fixture();

        assertThat(api.getBytes("/tickets/" + f.ticket + "/qr", testData.token(testData.user())).getStatusCode().value())
                .isEqualTo(404);
        assertThat(api.get("/tickets/" + f.ticket, testData.token(testData.user())).status()).isEqualTo(404);
    }

    @Test
    void used_ticket_has_no_qr() {
        Fixture f = fixture();
        api.post("/checkin", testData.token(f.staff), Map.of("qrToken", testData.qrToken(f.ticket), "gate", "A1"));

        Api.Response response = api.get("/tickets/" + f.ticket + "/qr", testData.token(f.buyer));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("TICKET_NOT_VALID");
    }

    @Test
    void ticket_keys_expose_only_public_keys() {
        Api.Response response = api.get("/ticket-keys", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("activeKeyId").asString()).isEqualTo("k1");
        assertThat(response.json().get("keys").get("k1").asString()).isEqualTo(TestKeys.publicBase64(TestKeys.QR_K1));
        assertThat(response.body()).doesNotContain(TestKeys.privateBase64(TestKeys.QR_K1));
    }

    private Fixture fixture() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        List<UUID> tickets = testData.issuedTickets(buyer, CreateOrderRequest.ga(event.id(), ga, 1));
        return new Fixture(buyer, tickets.getFirst(), testData.assignedStaff(event));
    }

    record Fixture(UUID buyer, UUID ticket, UUID staff) {}
}
