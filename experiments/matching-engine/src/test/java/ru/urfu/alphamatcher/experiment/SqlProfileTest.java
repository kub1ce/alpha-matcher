package ru.urfu.alphamatcher.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlProfileTest {
    @Test void percentilesUseNearestRankWithoutMutatingInput() {
        var samples = new ArrayList<>(List.of(30L, 10L, 20L, 40L));
        assertEquals(20L, SqlProfileMain.percentile(samples, .5));
        assertEquals(40L, SqlProfileMain.percentile(samples, .99));
        assertEquals(List.of(30L, 10L, 20L, 40L), samples);
        assertEquals(0L, SqlProfileMain.percentile(List.of(), .99));
    }
}
