package me.hanhyur.notify.api.notification.web;

import me.hanhyur.notify.api.notification.application.OperatorActionException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorAuditInputTest {

    private final OperatorAuditInput audit = new OperatorAuditInput(
            new IntegrationClientAccess.Settings(Map.of("orders", "키9"), null));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void rejectsShortKeysAsStandaloneValuesIncludingAfterAnEmbeddedOccurrence() {
        for (String text : List.of("참조 키9 확인", "기록값 (키9).", "조회키9번 확인 후 [키9] 재확인")) {
            assertThatThrownBy(() -> audit.validate("evidence", text, request))
                    .isInstanceOfSatisfying(OperatorActionException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(OperatorActionException.Reason.VALIDATION_FAILED);
                        assertThat(failure.field()).isEqualTo("evidence");
                        assertThat(failure.getMessage()).doesNotContain("키9");
                    });
        }
    }

    @Test
    void acceptsShortKeysInsideUnicodeWordsOrNumbers() {
        for (String text : List.of("조회키9 확인", "키9번 참조", "7키9 확인", "키9９ 참조")) {
            assertThatCode(() -> audit.validate("reason", text, request)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsCompactMobileNumbersInAuditNotes() {
        for (String field : List.of("reason", "evidence")) {
            assertThatThrownBy(() -> audit.validate(field, "수신처 01055551234로 확인했습니다.", request))
                    .isInstanceOfSatisfying(OperatorActionException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(OperatorActionException.Reason.VALIDATION_FAILED);
                        assertThat(failure.field()).isEqualTo(field);
                        assertThat(failure.getMessage()).doesNotContain("01055551234");
                    });
        }
    }

    @Test
    void acceptsSandboxReferencesWithNumericGroupsResemblingAPhoneNumber() {
        for (String reference : List.of("sandbox-00000010-1234-5678-0123-456789012345",
                "sandbox-00000010-1234-5678-0123-01055551234a")) {
            assertThatCode(() -> audit.validate("evidence", "공급자 접수 번호 " + reference + " 확인", request))
                    .doesNotThrowAnyException();
        }
    }
}
