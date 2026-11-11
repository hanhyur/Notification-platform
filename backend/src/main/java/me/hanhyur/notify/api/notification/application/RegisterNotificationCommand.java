package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;

import java.time.Instant;

public record RegisterNotificationCommand(
        String customerId,
        Channel channel,
        String recipient,
        String title,
        String content,
        Instant scheduledAt
) {
    @Override
    public String toString() {
        return "RegisterNotificationCommand[channel=" + channel
                + ", scheduledAt=" + scheduledAt + "]";
    }
}
