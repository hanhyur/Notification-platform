package me.hanhyur.notify.api.notification.infrastructure;

import me.hanhyur.notify.api.notification.application.NotificationRegistered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class NotificationRegistrationLogger {

    private static final Logger log = LoggerFactory.getLogger(NotificationRegistrationLogger.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void logRegistration(NotificationRegistered event) {
        log.info("notification_registered notificationId={} dispatchId={} eventId={}",
                event.notificationId(), event.dispatchId(), event.eventId());
    }
}
