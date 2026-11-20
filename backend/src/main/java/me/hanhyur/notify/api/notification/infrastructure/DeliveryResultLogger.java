package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.domain.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class DeliveryResultLogger {

    private static final Logger log = LoggerFactory.getLogger(DeliveryResultLogger.class);
    private static final String MESSAGE = "delivery_result_recorded notificationId={} dispatchId={} eventId={} status={}";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void logResult(DeliveryResultRecorded result) {
        if (result.status() != NotificationStatus.ACCEPTED) {
            log.warn(MESSAGE, result.notificationId(), result.dispatchId(), result.eventId(), result.status());

            return;
        }

        log.info(MESSAGE, result.notificationId(), result.dispatchId(), result.eventId(), result.status());
    }
}
