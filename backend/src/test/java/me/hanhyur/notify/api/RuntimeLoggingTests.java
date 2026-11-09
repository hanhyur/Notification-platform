package me.hanhyur.notify.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
public class RuntimeLoggingTests {

    private final Logger rootLogger =
            (Logger) LoggerFactory.getLogger(
                    org.slf4j.Logger.ROOT_LOGGER_NAME
            );

    private final Logger applicationLogger =
            (Logger) LoggerFactory.getLogger(RuntimeLoggingTests.class);

    @Test
    void usesWarnForRootLogger() {
        assertEquals(Level.WARN, rootLogger.getEffectiveLevel());
    }

    @Test
    void usesInfoForApplicationLogger() {
        assertEquals(Level.INFO, applicationLogger.getEffectiveLevel());
        assertTrue(applicationLogger.isInfoEnabled());
        assertFalse(applicationLogger.isDebugEnabled());
    }

    @Test
    void connectsStartedConsoleAppenderToRoot() {
        Appender<ILoggingEvent> appender =
                rootLogger.getAppender("CONSOLE");

        assertNotNull(appender);

        ConsoleAppender<?> console =
                assertInstanceOf(ConsoleAppender.class, appender);

        assertTrue(console.isStarted());
        assertEquals("System.out", console.getTarget());
    }

    @Test
    void writesFormattedInfoLog(CapturedOutput output) {
        String marker = "runtime-info-check";

        applicationLogger.info(marker);

        String line = output.getOut().lines()
                .filter(value -> value.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "INFO 로그를 표준 출력에서 찾을 수 없습니다."
                ));

        assertAll(
                () -> assertTrue(
                        line.matches("^[0-9]{4}-[0-9]{2}-[0-9]{2}.*")
                ),
                () -> assertTrue(line.contains("INFO")),
                () -> assertTrue(line.contains("[backend]")),
                () -> assertTrue(line.contains(
                        RuntimeLoggingTests.class.getSimpleName()
                ))
        );
    }

    @Test
    void filtersDebugLog(CapturedOutput output) {
        String marker = "runtime-debug-check";

        applicationLogger.debug(marker);

        assertFalse(output.getAll().contains(marker));
    }
}
