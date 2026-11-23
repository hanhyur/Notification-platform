package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;

final class RecipientMasking {

    private RecipientMasking() {}

    static String mask(Channel channel, String recipient) {
        String masked = switch (channel) {
            case EMAIL -> {
                int at = recipient.lastIndexOf('@');
                if (at < 1) {
                    yield "***";
                }
                int first = recipient.offsetByCodePoints(0, 1);
                String prefix = first < at ? recipient.substring(0, first) : "";
                yield prefix + "***" + recipient.substring(at);
            }
            case SMS -> "***" + recipient.substring(Math.max(0, recipient.length() - 4));
            case KAKAOTALK -> "***";
        };
        return masked.equals(recipient) ? "•••" : masked;
    }
}
