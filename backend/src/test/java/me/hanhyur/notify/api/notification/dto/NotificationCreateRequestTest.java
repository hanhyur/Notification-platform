package me.hanhyur.notify.api.notification.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import me.hanhyur.notify.api.notification.domain.Channel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NotificationCreateRequestTest {

    private static final String EMAIL_RECIPIENT = "sample@example.com";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @ParameterizedTest(name = "{0}: valid recipient [{index}]")
    @MethodSource("validRecipients")
    void acceptsValidRecipient(Channel channel, String recipient) {
        assertTrue(validator.validate(request(channel, recipient)).isEmpty());
    }

    static Stream<Arguments> validRecipients() {
        return Stream.of(
                Arguments.of(Channel.EMAIL, EMAIL_RECIPIENT),
                Arguments.of(Channel.SMS, "+" + "1".repeat(8)),
                Arguments.of(Channel.SMS, "+" + "1".repeat(15)),
                Arguments.of(Channel.KAKAOTALK, "a"),
                Arguments.of(Channel.KAKAOTALK, "a".repeat(100))
        );
    }

    @ParameterizedTest(name = "{0}: missing or blank [{index}]")
    @MethodSource("missingOrBlankTexts")
    void rejectsMissingOrBlankText(String field, String value) {
        assertEquals(Set.of(field), invalidFields(requestWithText(field, value)));
    }

    static Stream<Arguments> missingOrBlankTexts() {
        return Stream.of("customerId", "recipient", "title", "content")
                .flatMap(field -> Stream.<String>of(null, "", " \t\n ")
                        .map(value -> Arguments.of(field, value)));
    }

    @Test
    void rejectsMissingChannel() {
        assertEquals(
                Set.of("channel"),
                invalidFields(request(null, EMAIL_RECIPIENT))
        );
    }

    @ParameterizedTest(name = "{0}: accepts Unicode limit {1}")
    @MethodSource("textLimits")
    void acceptsUnicodeAtTextLimit(String field, int limit) {
        assertTrue(validator.validate(
                requestWithText(field, "\uD83D\uDE00".repeat(limit))
        ).isEmpty());
    }

    @ParameterizedTest(name = "{0}: rejects ASCII beyond {1}")
    @MethodSource("textLimits")
    void rejectsASCIIBeyondTextLimit(String field, int limit) {
        assertEquals(
                Set.of(field),
                invalidFields(requestWithText(field, "a".repeat(limit + 1)))
        );
    }

    @ParameterizedTest(name = "{0}: rejects Unicode beyond {1}")
    @MethodSource("textLimits")
    void rejectsUnicodeBeyondTextLimit(String field, int limit) {
        assertEquals(
                Set.of(field),
                invalidFields(requestWithText(field, "\uD83D\uDE00".repeat(limit + 1)))
        );
    }

    static Stream<Arguments> textLimits() {
        return Stream.of(
                Arguments.of("customerId", 100),
                Arguments.of("title", 100),
                Arguments.of("content", 2000)
        );
    }

    @ParameterizedTest(name = "{0}: invalid recipient [{index}]")
    @MethodSource("invalidRecipients")
    void rejectsInvalidRecipient(Channel channel, String recipient) {
        assertEquals(
                Set.of("recipient"),
                invalidFields(request(channel, recipient))
        );
    }

    static Stream<Arguments> invalidRecipients() {
        return Stream.of(
                Arguments.of(Channel.EMAIL, "plain-address"),
                Arguments.of(Channel.EMAIL, "sample@"),
                Arguments.of(Channel.EMAIL, "@example.com"),
                Arguments.of(Channel.EMAIL, "a".repeat(321)),
                Arguments.of(Channel.SMS, "+0" + "1".repeat(7)),
                Arguments.of(Channel.SMS, "+" + "1".repeat(7)),
                Arguments.of(Channel.SMS, "+" + "1".repeat(16)),
                Arguments.of(Channel.SMS, "1".repeat(8)),
                Arguments.of(Channel.SMS, "+1abcdefg"),
                Arguments.of(Channel.SMS, "+" + "1".repeat(8) + "\n"),
                Arguments.of(Channel.KAKAOTALK, "a".repeat(101))
        );
    }

    @Test
    void leavesScheduleBusinessValidationToSchedulePolicy() {
        NotificationCreateRequest request = new NotificationCreateRequest(
                "customer-1001",
                Channel.EMAIL,
                EMAIL_RECIPIENT,
                "예약 안내",
                "예약이 접수되었습니다.",
                Instant.EPOCH
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    private static NotificationCreateRequest request(Channel channel, String recipient) {
        return new NotificationCreateRequest(
                "customer-1001",
                channel,
                recipient,
                "예약 안내",
                "예약이 접수되었습니다.",
                null
        );
    }

    private static NotificationCreateRequest requestWithText(String field, String value) {
        return new NotificationCreateRequest(
                field.equals("customerId") ? value : "customer-1001",
                Channel.EMAIL,
                field.equals("recipient") ? value : EMAIL_RECIPIENT,
                field.equals("title") ? value : "예약 안내",
                field.equals("content") ? value : "예약이 접수되었습니다.",
                null
        );
    }

    private static Set<String> invalidFields(NotificationCreateRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

}