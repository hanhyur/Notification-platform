package me.hanhyur.notify.api.notification.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
final class QueryCursors {

    record Position(Instant time, Integer attemptNo, UUID id, Instant reference) {}

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final JsonMapper json;

    QueryCursors(JsonMapper json, @Value("${notification.query.cursor-signing-key:}") String configuredKey) {
        this.json = json;
        byte[] bytes;
        if (configuredKey.isEmpty()) {
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
        } else {
            bytes = Base64.getDecoder().decode(configuredKey);
            if (bytes.length < 32) {
                throw new IllegalArgumentException("조회 커서 서명 키는 32바이트 이상이어야 합니다.");
            }
        }
        key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    byte[] scope(String resource, Object filters) {
        return json.writeValueAsBytes(List.of(resource, filters));
    }

    String encode(byte[] scope, Position position) {
        byte[] payload = json.writeValueAsBytes(position);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(signature(scope, payload));
    }

    Position decode(byte[] scope, String token) {
        if (token == null) {
            return null;
        }
        int dot = token.indexOf('.');
        if (token.length() > 2048 || dot <= 0 || dot != token.lastIndexOf('.')) {
            throw NotificationQueryException.malformed();
        }
        try {
            String encoded = token.substring(0, dot);
            byte[] payload = DECODER.decode(encoded);
            String tag = token.substring(dot + 1);
            byte[] supplied = DECODER.decode(tag);
            if (!ENCODER.encodeToString(payload).equals(encoded) || !ENCODER.encodeToString(supplied).equals(tag)
                    || !MessageDigest.isEqual(signature(scope, payload), supplied)) {
                throw NotificationQueryException.malformed();
            }
            return json.readValue(payload, Position.class);
        } catch (IllegalArgumentException | JacksonException e) {
            throw NotificationQueryException.malformed();
        }
    }

    private byte[] signature(byte[] scope, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(key.getAlgorithm());
            mac.init(key);
            mac.update(scope);
            mac.update((byte) 0);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("조회 커서를 서명할 수 없습니다.", e);
        }
    }
}
