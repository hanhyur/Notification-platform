package me.hanhyur.notify.api.notification.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(RegisterNotificationCommand command) {
        return hash(command.customerId(), command.channel().name(), command.recipient(), command.title(),
                command.content(), command.scheduledAt() == null ? "" : command.scheduledAt().toString());
    }

    static String of(UUID notificationId, OperatorActionCommand command) {
        return hash(notificationId.toString(), command.kind().name(), Long.toString(command.expectedVersion()),
                command.resolution() == null ? "" : command.resolution().name(), command.note());
    }

    private static String hash(String... fields) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        for (String field : fields) {
            append(digest, field);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
