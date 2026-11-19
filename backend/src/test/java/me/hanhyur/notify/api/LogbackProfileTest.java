package me.hanhyur.notify.api;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class LogbackProfileTest {

    private static final String REQUEST_ID = "profile-request-42";
    private static final String REQUEST_MESSAGE = "요청 로그 확인";
    private static final String BACKGROUND_MESSAGE = "백그라운드 로그 확인";
    private static final String BEFORE_ROLLING = "압축 전 로그 확인";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"default", "local", "test"})
    void nonProductionProfilesLogOnlyToConsole(String profile) throws Exception {
        Path logFile = directory.resolve("requested.log");
        String console = runProfile(profile, "--logging.file.name=" + logFile);

        assertEvents(console);
        assertEquals(profile.equals("local"), console.contains("profile-debug-marker"));
        assertEquals(!profile.equals("test"), console.contains("root-info-marker"));
        assertFalse(Files.exists(logFile));
        assertFalse(Files.exists(directory.resolve("logs")));
    }

    @Test
    void productionWritesTheSameEventsToConsoleAndDefaultFile() throws Exception {
        String console = runProfile("prod");
        Path logFile = directory.resolve("logs/notification-platform.log");

        assertEvents(console);
        assertTrue(Files.isRegularFile(logFile), "운영 기본 경로에 로그 파일이 생성되어야 합니다.");
        assertEvents(Files.readString(logFile));
        assertFalse(console.contains("profile-debug-marker"));
        assertTrue(console.contains("root-info-marker"));
    }

    @Test
    void productionRollsTheConfiguredFileIntoGzipArchives() throws Exception {
        Path logFile = directory.resolve("custom/dispatch.log");
        runProfile("prod", "--logging.file.name=" + logFile,
                "--logging.logback.rollingpolicy.max-file-size=1KB");

        assertTrue(Files.isRegularFile(logFile), "재정의한 경로에 운영 로그 파일이 생성되어야 합니다.");
        assertFalse(Files.exists(directory.resolve("logs/notification-platform.log")));
        List<Path> archives;
        try (var files = Files.walk(logFile.getParent())) {
            archives = files.filter(file -> file.getFileName().toString().endsWith(".gz")).toList();
        }
        StringBuilder archivedLogs = new StringBuilder();
        for (Path archive : archives) {
            try (var gzip = new GZIPInputStream(Files.newInputStream(archive))) {
                archivedLogs.append(new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertTrue(archivedLogs.toString().contains(BEFORE_ROLLING),
                "크기 제한을 넘긴 로그가 gzip 보관 파일에서 읽혀야 합니다.");
    }

    private static void assertEvents(String output) {
        assertEquals(1L, output.lines().filter(line ->
                line.contains("[requestId=" + REQUEST_ID + "]") && line.endsWith(REQUEST_MESSAGE)).count());
        assertEquals(1L, output.lines().filter(line ->
                line.contains("[requestId=none]") && line.endsWith(BACKGROUND_MESSAGE)).count());
    }

    private String runProfile(String profile, String... properties) throws Exception {
        String classpath = System.getProperty("loggingTestClasspath", System.getProperty("java.class.path"));
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-cp", classpath, Probe.class.getName(),
                "--spring.config.location=classpath:/"));
        if (!profile.equals("default")) {
            command.add("--spring.profiles.active=" + profile);
        }
        command.addAll(List.of(properties));
        Path console = directory.resolve("console.txt");
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(console.toFile());
        builder.environment().clear();
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "로깅 테스트 JVM 실행 시간 초과");
            String output = Files.readString(console);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Probe {

        public static void main(String[] args) {
            try (var context = new SpringApplicationBuilder(Probe.class).web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {
                Logger log = LoggerFactory.getLogger(Probe.class);
                log.info("{} {}", BEFORE_ROLLING, "가".repeat(1024));
                try (var ignored = MDC.putCloseable("requestId", REQUEST_ID)) {
                    log.info(REQUEST_MESSAGE);
                }
                log.info(BACKGROUND_MESSAGE);
                log.debug("profile-debug-marker");
                LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).info("root-info-marker");
            } finally {
                ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
            }
        }
    }
}
