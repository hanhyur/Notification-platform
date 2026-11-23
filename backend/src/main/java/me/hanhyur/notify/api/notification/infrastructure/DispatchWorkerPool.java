package me.hanhyur.notify.api.notification.infrastructure;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.concurrent.*;

public final class DispatchWorkerPool implements AutoCloseable {

    private static final String REQUEST_ID = "requestId";

    private final ThreadPoolExecutor executor;

    public DispatchWorkerPool(int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("발송 작업자 수는 양수이고 대기열 크기는 0 이상이어야 합니다.");
        }
        executor = createExecutor(workers, queueCapacity);
    }

    private static ThreadPoolExecutor createExecutor(int workers, int queueCapacity) {
        var threads = Thread.ofPlatform()
                .name("notification-dispatch-", 1)
                .inheritInheritableThreadLocals(false)
                .factory();

        BlockingQueue<Runnable> queue = queueCapacity == 0
                ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(queueCapacity);

        return new ThreadPoolExecutor(
                workers,
                workers,
                0,
                TimeUnit.MILLISECONDS,
                queue,
                threads,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void execute(Runnable work) {
        Objects.requireNonNull(work);
        String requestId = MDC.get(REQUEST_ID);
        executor.execute(() -> {
            try {
                if (requestId != null) {
                    MDC.put(REQUEST_ID, requestId);
                }
                work.run();
            } finally {
                MDC.clear();
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException stopping) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
