package me.hanhyur.notify.api.notification.config;

import me.hanhyur.notify.api.notification.application.NotificationOutboxPublicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class OutboxSchedulingConfigurationTest {

    private final NotificationOutboxPublicationService service = mock();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxSchedulingConfiguration.class)
            .withBean(NotificationOutboxPublicationService.class, () -> service);

    @Test
    void doesNotRegisterScheduledWorkWhenPollingIsNotConfigured() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(scheduledTasks(context).isEmpty());
            verifyNoInteractions(service);
        });
    }

    @Test
    void doesNotRegisterScheduledWorkWhenPollingIsExplicitlyDisabled() {
        contextRunner.withPropertyValues("notification.outbox.polling.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(scheduledTasks(context).isEmpty());
                    verifyNoInteractions(service);
                });
    }

    @Test
    void registersOneFixedDelayTaskWithTheConfiguredDelayAndBatchSize() {
        TaskScheduler taskScheduler = mock();
        ScheduledFuture<?> future = mock();
        when(taskScheduler.getClock()).thenReturn(Clock.systemUTC());
        doReturn(future).when(taskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        doReturn(future).when(taskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));

        contextRunner.withPropertyValues(
                        "notification.outbox.polling.enabled=true",
                        "notification.outbox.polling.fixed-delay=3s",
                        "notification.outbox.polling.batch-size=7"
                )
                .withBean(TaskScheduler.class, () -> taskScheduler)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    List<ScheduledTask> scheduled = scheduledTasks(context);
                    assertEquals(1, scheduled.size());
                    FixedDelayTask task = assertInstanceOf(
                            FixedDelayTask.class, scheduled.getFirst().getTask());
                    assertEquals(Duration.ofSeconds(3), task.getIntervalDuration());
                    verifyNoInteractions(service);

                    task.getRunnable().run();

                    verify(service).publishPending(7);
                    verifyNoMoreInteractions(service);
                });
    }

    @Test
    void runsTheNextScheduledBatchAfterThePublicationServiceFails() {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch nextBatch = new CountDownLatch(1);
        when(service.publishPending(9)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("일시적인 Outbox 발행 실패");
            }
            nextBatch.countDown();
            return 0;
        });

        contextRunner.withPropertyValues(
                        "notification.outbox.polling.enabled=true",
                        "notification.outbox.polling.fixed-delay=100ms",
                        "notification.outbox.polling.batch-size=9"
                )
                .withBean("taskScheduler", ThreadPoolTaskScheduler.class, () -> {
                    ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
                    taskScheduler.setPoolSize(1);
                    taskScheduler.setThreadNamePrefix("outbox-scheduling-test-");
                    return taskScheduler;
                })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(nextBatch.await(5, TimeUnit.SECONDS),
                            "발행 실패 후 다음 예약 배치가 실행되어야 합니다.");
                    assertTrue(calls.get() >= 2);
                    verify(service, atLeast(2)).publishPending(9);
                });
    }

    private List<ScheduledTask> scheduledTasks(ApplicationContext context) {
        return context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .toList();
    }
}
