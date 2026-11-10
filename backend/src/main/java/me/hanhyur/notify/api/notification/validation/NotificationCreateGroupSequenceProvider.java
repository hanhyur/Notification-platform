package me.hanhyur.notify.api.notification.validation;

import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.dto.NotificationCreateRequest;
import org.hibernate.validator.spi.group.DefaultGroupSequenceProvider;

import java.util.ArrayList;
import java.util.List;

public class NotificationCreateGroupSequenceProvider
        implements DefaultGroupSequenceProvider<NotificationCreateRequest> {

    @Override
    public List<Class<?>> getValidationGroups(
            Class<?> beanType,
            NotificationCreateRequest request
    ) {
        List<Class<?>> groups = new ArrayList<>();
        groups.add(NotificationCreateRequest.class);

        if (request == null || request.channel() == null) {
            return groups;
        }

        if (request.channel() == Channel.EMAIL) {
            groups.add(NotificationCreateRequest.EmailChecks.class);
        }

        if (request.channel() == Channel.SMS) {
            groups.add(NotificationCreateRequest.SmsChecks.class);
        }

        if (request.channel() == Channel.KAKAOTALK) {
            groups.add(NotificationCreateRequest.KakaoChecks.class);
        }

        return groups;
    }
}
