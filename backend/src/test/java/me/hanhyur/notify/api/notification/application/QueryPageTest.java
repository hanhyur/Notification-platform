package me.hanhyur.notify.api.notification.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPageTest {

    @Test
    void anExtraRowSignalsAnotherPageAndTheCursorBelongsToTheLastVisibleRow() {
        var page = QueryPage.from(List.of("third", "second", "first"), 2, row -> "after-" + row);

        assertThat(page.items()).containsExactly("third", "second");
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("after-second");
    }

    @Test
    void exactlyOneFullPageNeedsNoCursor() {
        var page = QueryPage.from(List.of("second", "first"), 2, row -> "after-" + row);

        assertThat(page.items()).containsExactly("second", "first");
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
