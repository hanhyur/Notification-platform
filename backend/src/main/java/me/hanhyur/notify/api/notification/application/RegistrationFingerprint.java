package me.hanhyur.notify.api.notification.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class RegistrationFingerprint {

    private RegistrationFingerprint() {
    }

    static String of(RegisterNotificationCommand command) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        append(digest, command.customerId());
        append(digest, command.channel().name());
        append(digest, command.recipient());
        append(digest, command.title());
        append(digest, command.content());
        append(digest, command.scheduledAt() == null ? "" : command.scheduledAt().toString());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
