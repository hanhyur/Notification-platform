package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.domain.DeliveryDecision;
import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import me.hanhyur.notify.api.notification.domain.ProviderResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManualRetryStateTest {

    @Test
    void exhaustedRetriesStartAFreshCycleWithAnImmediatelyDueOutboxEvent() {
        Instant created = Instant.parse("2026-11-01T00:00:00Z");
        var sending = new Notification(UUID.randomUUID(), UUID.randomUUID(), "orders", Channel.EMAIL,
                "orders@example.com", "주문", "주문 상태를 알려드립니다.", NotificationStatus.SENDING, null,
                created, created, 2, 3, 7);
        var entity = NotificationJpaEntity.fromDomain(sending);
        entity.finish(DeliveryDecision.decide(ProviderResult.Outcome.TRANSIENT_REJECTION, 3), created.plusSeconds(1));
        var failed = entity.toDomain();
        assertThat(failed.status()).isEqualTo(NotificationStatus.FAILED);

        UUID newDispatchId = UUID.randomUUID();
        Instant clicked = created.plusSeconds(60);
        var event = entity.retryManually(newDispatchId, clicked);

        var queued = entity.toDomain();
        assertThat(queued.status()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(queued.currentDispatchId()).isEqualTo(newDispatchId);
        assertThat(queued.generation()).isEqualTo(failed.generation() + 1);
        assertThat(queued.version()).isEqualTo(failed.version() + 1);
        assertThat(queued.updatedAt()).isEqualTo(clicked);
        assertThat(queued.retryCount()).isZero();
        assertThat(entity.completedAt()).isNull();
        assertThat(entity.acceptedAt()).isNull();
        assertThat(entity.lastErrorCode()).isNull();
        assertThat(entity.errorSummary()).isNull();
        assertThat(event.notificationId()).isEqualTo(failed.notificationId());
        assertThat(event.dispatchId()).isEqualTo(newDispatchId);
        assertThat(event.generation()).isEqualTo(queued.generation());
        assertThat(event.occurredAt()).isEqualTo(clicked);
        assertThat(event.availableAt()).isEqualTo(clicked);
    }
}
