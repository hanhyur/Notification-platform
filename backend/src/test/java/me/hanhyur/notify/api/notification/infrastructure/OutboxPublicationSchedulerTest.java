package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationOutboxPublicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPublicationSchedulerTest {

    private static final int BATCH_SIZE = 3;

    private final NotificationOutboxPublicationService service = mock();
    private final OutboxPublicationScheduler scheduler =
            new OutboxPublicationScheduler(service, BATCH_SIZE);

    @ParameterizedTest
    @ValueSource(ints = {0, BATCH_SIZE})
    void publishesExactlyOneBatchPerTickEvenWhenEmptyOrFull(int completedCount) {
        when(service.publishPending(BATCH_SIZE)).thenReturn(completedCount, 0);

        scheduler.publishBatch();

        verify(service).publishPending(BATCH_SIZE);
        verifyNoMoreInteractions(service);
    }

    @Test
    void propagatesFailureWithoutRetryingAndAllowsTheNextTick() {
        IllegalStateException failure = new IllegalStateException("Outbox 발행 실패");
        when(service.publishPending(BATCH_SIZE)).thenThrow(failure).thenReturn(0);

        assertSame(failure, assertThrows(IllegalStateException.class, scheduler::publishBatch));

        verify(service).publishPending(BATCH_SIZE);
        verifyNoMoreInteractions(service);

        assertDoesNotThrow(scheduler::publishBatch);

        verify(service, times(2)).publishPending(BATCH_SIZE);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBatchSizes(int batchSize) {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxPublicationScheduler(service, batchSize));

        verifyNoInteractions(service);
    }
}
