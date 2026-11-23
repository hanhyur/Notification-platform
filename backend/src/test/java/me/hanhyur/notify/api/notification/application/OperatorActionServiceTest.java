package me.hanhyur.notify.api.notification.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OperatorActionServiceTest {

    private final OperatorActionStore store = mock(OperatorActionStore.class);
    private final OperatorNotificationHistory history = mock(OperatorNotificationHistory.class);
    private final OperatorActionService service = new OperatorActionService(store, history, Clock.systemUTC());
    private final UUID operator = UUID.randomUUID();
    private final UUID notification = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final OperatorActionCommand retry = OperatorActionCommand.retry(8, "공급자 설정을 수정한 뒤 재처리합니다.");

    @Test
    void aRepeatedCommandReadsTheCurrentDetailWithoutLockingOrChangingTheNotification() {
        String hash = RequestFingerprint.of(notification, retry);
        var receipt = new OperatorActionStore.Receipt(hash, notification);
        when(store.reserve(operator, "RETRY", key, notification, hash)).thenReturn(Optional.of(receipt));
        var current = mock(IntegrationNotificationDetail.class);
        when(history.detail(notification, operator)).thenReturn(Optional.of(current));

        var result = service.execute(operator, notification, key, retry);

        assertThat(result).isEqualTo(new OperatorActionService.Result(current, true));
        verify(store).reserve(operator, "RETRY", key, notification, hash);
        verifyNoMoreInteractions(store);
    }

    @Test
    void aKeyAlreadyUsedForAnotherCommandBodyIsRejectedBeforeAnyStateChecks() {
        String hash = RequestFingerprint.of(notification, retry);
        var original = OperatorActionCommand.retry(7, retry.note());
        var receipt = new OperatorActionStore.Receipt(RequestFingerprint.of(notification, original), notification);
        when(store.reserve(operator, "RETRY", key, notification, hash)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> service.execute(operator, notification, key, retry))
                .isInstanceOfSatisfying(OperatorActionException.class,
                        e -> assertThat(e.reason()).isEqualTo(OperatorActionException.Reason.STATE_OR_VERSION_CONFLICT));
        verify(store).reserve(operator, "RETRY", key, notification, hash);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(history);
    }
}
