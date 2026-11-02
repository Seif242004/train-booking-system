package com.trainbooking.ticket.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.trainbooking.ticket.ticket.service.QrImages;
import com.trainbooking.ticket.ticket.service.QrTokens;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class QrTokensTest {

    private final QrTokens tokens = new QrTokens("a-long-enough-signing-key");

    @Test
    void aGenuineToken_givesBackItsReference() {
        String token = tokens.issue("TB-ABC234");

        assertThat(token).startsWith("TB-ABC234.");
        assertThat(tokens.verify(token)).contains("TB-ABC234");
    }

    @Test
    void everyTokenIsDifferent_evenForTheSameReference() {
        assertThat(tokens.issue("TB-ABC234")).isNotEqualTo(tokens.issue("TB-ABC234"));
    }

    @Test
    void aChangedReference_aChangedSignature_orAnotherKey_isNotGenuine() {
        String token = tokens.issue("TB-ABC234");
        String[] parts = token.split("\\.");

        assertThat(tokens.verify(token.replace("TB-ABC234", "TB-ZZZ999"))).isEmpty();
        assertThat(tokens.verify(parts[0] + "." + parts[1] + "." + parts[2].substring(1) + "A")).isEmpty();
        assertThat(tokens.verify(parts[0] + "." + parts[1] + ".")).isEmpty();
        assertThat(new QrTokens("another-signing-key-here").verify(token)).isEmpty();
    }

    @Test
    void garbage_isNotGenuine() {
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("no-dots-at-all")).isEmpty();
        assertThat(tokens.verify("one.dot")).isEmpty();
        assertThat(tokens.verify(".a.b")).isEmpty();
    }

    @Test
    void aMissingOrShortKey_stopsTheService() {
        assertThatThrownBy(() -> new QrTokens(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("TICKET_SIGNING_KEY");
        assertThatThrownBy(() -> new QrTokens("short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theQrCode_decodesBackToTheToken() throws Exception {
        String token = tokens.issue("TB-ABC234");

        var image = ImageIO.read(new ByteArrayInputStream(QrImages.png(token, 260)));
        String decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();

        assertThat(decoded).isEqualTo(token);
        assertThat(tokens.verify(decoded)).contains("TB-ABC234");
    }
}
