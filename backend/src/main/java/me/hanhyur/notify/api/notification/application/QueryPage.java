package me.hanhyur.notify.api.notification.application;

import java.util.List;
import java.util.function.Function;

public record QueryPage<T>(List<T> items, String nextCursor, boolean hasNext) {

    static <T> QueryPage<T> from(List<T> rows, int size, Function<T, String> cursorFor) {
        boolean hasNext = rows.size() > size;

        int visibleCount = Math.min(size, rows.size());
        List<T> items = List.copyOf(rows.subList(0, visibleCount));

        String nextCursor = hasNext ? cursorFor.apply(items.getLast()) : null;

        return new QueryPage<>(items, nextCursor, hasNext);
    }
}
