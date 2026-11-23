package me.hanhyur.notify.api.notification.application;

import me.hanhyur.notify.api.notification.domain.Notification;
import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorActionCommandTest {

    private final Notification notification = mock(Notification.class);

    @Test
    void evidenceDistinguishesProviderAcceptanceFromConfirmedNonDelivery() {
        when(notification.status()).thenReturn(NotificationStatus.UNKNOWN);
        when(notification.version()).thenReturn(9L);
        String evidence = "공급자 조회에서 해당 요청의 접수 기록을 확인했습니다.";
        var accepted = OperatorActionCommand.resolve(9, OperatorActionCommand.Resolution.ACCEPTED, evidence);
        var notSent = OperatorActionCommand.resolve(9, OperatorActionCommand.Resolution.NOT_SENT, evidence);

        assertThat(accepted.resultingStatus(notification)).isEqualTo(NotificationStatus.ACCEPTED);
        assertThat(notSent.resultingStatus(notification)).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void retryRequiresBothTheFailedStatusAndTheVersionSeenByTheOperator() {
        var command = OperatorActionCommand.retry(8, "공급자 설정을 확인하고 다시 요청합니다.");
        when(notification.version()).thenReturn(8L);
        when(notification.status()).thenReturn(NotificationStatus.UNKNOWN);
        assertConflict(command);

        when(notification.status()).thenReturn(NotificationStatus.FAILED);
        when(notification.version()).thenReturn(9L);
        assertConflict(command);

        when(notification.version()).thenReturn(8L);
        assertThat(command.resultingStatus(notification)).isEqualTo(NotificationStatus.QUEUED);
    }

    private void assertConflict(OperatorActionCommand command) {
        assertThatThrownBy(() -> command.resultingStatus(notification))
                .isInstanceOfSatisfying(OperatorActionException.class, e -> assertThat(e.reason())
                        .isEqualTo(OperatorActionException.Reason.STATE_OR_VERSION_CONFLICT));
    }
}
