package me.hanhyur.notify.api.notification.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import me.hanhyur.notify.api.notification.domain.Channel;
import me.hanhyur.notify.api.notification.validation.NotificationCreateGroupSequenceProvider;
import org.hibernate.validator.constraints.CodePointLength;
import org.hibernate.validator.group.GroupSequenceProvider;

import java.time.Instant;

@GroupSequenceProvider(NotificationCreateGroupSequenceProvider.class)
public record NotificationCreateRequest(
        @NotBlank
        @CodePointLength(max = 100)
        String customerId,

        @NotNull
        Channel channel,

        @NotBlank
        @CodePointLength(max = 320)
        @Email(groups = EmailChecks.class)
        @Pattern(
                regexp = "^\\+[1-9][0-9]{7,14}$",
                groups = SmsChecks.class
        )
        @CodePointLength(max = 100, groups = KakaoChecks.class)
        String recipient,

        @NotBlank
        @CodePointLength(max = 100)
        String title,

        @NotBlank
        @CodePointLength(max = 2000)
        String content,

        Instant scheduledAt
) {
    public interface EmailChecks {

    }

    public interface SmsChecks {

    }

    public interface KakaoChecks {

    }
}
