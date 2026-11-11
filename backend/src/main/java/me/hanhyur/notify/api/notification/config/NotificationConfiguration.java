package me.hanhyur.notify.api.notification.config;

import me.hanhyur.notify.api.notification.domain.NotificationFactory;
import me.hanhyur.notify.api.notification.domain.NotificationSchedulePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

    @Bean
    Clock notificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    NotificationSchedulePolicy notificationSchedulePolicy() {
        return new NotificationSchedulePolicy();
    }

    @Bean
    NotificationFactory notificationFactory(
            Clock clock,
            NotificationSchedulePolicy schedulePolicy
    ) {
        return new NotificationFactory(clock, schedulePolicy);
    }
}
