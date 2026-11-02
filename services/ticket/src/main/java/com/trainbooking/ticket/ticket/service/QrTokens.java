package com.trainbooking.ticket.ticket.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The token in the QR code: {@code reference.nonce.signature}. The signature is an HMAC-SHA256 of the reference and the nonce with a
 * key only this service knows, so a token cannot be made up or changed (a scanner that calls {@link #verify} can trust the
 * reference in it). Whether the ticket is still valid is a separate fact, kept in the ticket row (INVALIDATED after a cancellation).
 */
public class QrTokens {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public QrTokens(String signingKey) {
        if (signingKey == null || signingKey.length() < 16) {
            throw new IllegalStateException("TICKET_SIGNING_KEY must be set (at least 16 characters): it signs the QR codes.");
        }
        this.key = signingKey.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String reference) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        String payload = reference + "." + ENCODER.encodeToString(nonce);
        return payload + "." + sign(payload);
    }

    /** @return the reference in the token if the signature is genuine, empty for anything else */
    public Optional<String> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int last = token.lastIndexOf('.');
        int first = token.indexOf('.');
        if (last <= first || first <= 0) {
            return Optional.empty();
        }
        String payload = token.substring(0, last);
        // Constant-time compare: the answer must not reveal how much of a forged signature was right.
        boolean genuine = MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), token.substring(last + 1).getBytes(StandardCharsets.UTF_8));
        return genuine ? Optional.of(token.substring(0, first)) : Optional.empty();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
