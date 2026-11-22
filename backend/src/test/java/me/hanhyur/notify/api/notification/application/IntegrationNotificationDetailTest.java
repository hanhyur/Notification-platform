package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationNotificationDetailTest {

    @ParameterizedTest
    @CsvSource({
            "EMAIL, sample@example.com, s***@example.com",
            "EMAIL, s***@example.com, •••",
            "SMS, +821012345678, ***5678",
            "KAKAOTALK, talk-user, ***",
            "KAKAOTALK, ***, •••"
    })
    void masksRecipientsEvenWhenTheyLookLikeAMaskedValue(Channel channel, String address, String expected) {
        var notification = new Notification(UUID.randomUUID(), UUID.randomUUID(), "customer", channel, address,
                "주문", "배송 안내", NotificationStatus.QUEUED, null, Instant.EPOCH, Instant.EPOCH, 1, 0, 0);

        var detail = IntegrationNotificationDetail.from(notification, null, null, null, null, null, List.of());

        assertThat(detail.recipientMasked()).isEqualTo(expected).isNotEqualTo(address);
        assertThat(detail.toString()).doesNotContain(address, "배송 안내");
    }
}
