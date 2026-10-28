package ru.urfu.alphamatcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppPaginationTest {
    @Test
    void windowsHaveTenRowsAndClampAfterFiltering() {
        assertEquals(new AppPagination.Window(0, 1, 0, 0), AppPagination.window(0, 80));
        assertEquals(new AppPagination.Window(0, 1, 1, 10), AppPagination.window(10, -2));
        assertEquals(new AppPagination.Window(2, 13, 21, 30), AppPagination.window(127, 2));
        assertEquals(new AppPagination.Window(12, 13, 121, 127), AppPagination.window(127, 500));
        assertEquals(new AppPagination.Window(0, 1, 1, 4), AppPagination.window(4, 12));
        assertThrows(IllegalArgumentException.class, () -> AppPagination.window(-1, 0));
    }
}
