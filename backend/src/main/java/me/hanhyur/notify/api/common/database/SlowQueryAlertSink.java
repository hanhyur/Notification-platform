package me.hanhyur.notify.api.common.database;

@FunctionalInterface
public interface SlowQueryAlertSink {

    void accept(SlowQueryAlert alert);
}
