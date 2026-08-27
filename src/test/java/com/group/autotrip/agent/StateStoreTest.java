package com.group.autotrip.agent;

import com.group.autotrip.common.model.BudgetItem;
import com.group.autotrip.common.model.DayPlan;
import com.group.autotrip.common.model.Itinerary;
import com.group.autotrip.common.model.MonitorTarget;
import com.group.autotrip.common.model.AlertType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {

    @TempDir
    Path tempDir;

    private StateStore newStore() {
        return new StateStore(tempDir.resolve("state.json").toString());
    }

    private static Itinerary sampleItinerary() {
        return new Itinerary(
                "杭州三日游",
                null,
                List.of(new DayPlan(1, LocalDate.of(2026, 8, 28), List.of(), "杭州", 0, "第一天")),
                3000,
                List.of("记得带伞"),
                com.group.autotrip.common.model.PlanStatus.DRAFT,
                List.of(new BudgetItem("住宿", 1500, "两晚"), new BudgetItem("门票", 600, "")));
    }

    @Test
    void monitorsSurviveRoundTrip() {
        StateStore store = newStore();
        Map<String, List<MonitorTarget>> monitors = Map.of(
                "u1", List.of(new MonitorTarget("郑州天气", AlertType.WEATHER, "郑州", "低于0度提醒")));
        store.saveMonitors(monitors);

        StateStore.State loaded = store.load();
        assertEquals(1, loaded.monitors().get("u1").size());
        assertEquals("郑州天气", loaded.monitors().get("u1").get(0).name());
        assertEquals(AlertType.WEATHER, loaded.monitors().get("u1").get(0).type());
    }

    @Test
    void itinerariesSurviveRoundTripWithDatesAndBudget() {
        StateStore store = newStore();
        store.saveItineraries(Map.of("u1", sampleItinerary()));

        StateStore.State loaded = store.load();
        Itinerary itinerary = loaded.itineraries().get("u1");
        assertNotNull(itinerary);
        assertEquals("杭州三日游", itinerary.title());
        assertEquals(LocalDate.of(2026, 8, 28), itinerary.days().get(0).date());
        assertEquals(2, itinerary.budgetItems().size());
        assertEquals("住宿", itinerary.budgetItems().get(0).category());
    }

    @Test
    void savingOnePartKeepsTheOther() {
        StateStore store = newStore();
        store.saveMonitors(Map.of("u1", List.of(new MonitorTarget("t", AlertType.OTHER, "", ""))));
        store.saveItineraries(Map.of("u1", sampleItinerary()));

        StateStore.State loaded = store.load();
        assertEquals(1, loaded.monitors().get("u1").size());
        assertNotNull(loaded.itineraries().get("u1"));
    }

    @Test
    void missingOrCorruptFileYieldsEmptyState() throws Exception {
        StateStore store = newStore();
        StateStore.State empty = store.load();
        assertTrue(empty.monitors().isEmpty());
        assertTrue(empty.itineraries().isEmpty());

        // 写入坏文件后读取仍返回空状态
        java.nio.file.Files.writeString(tempDir.resolve("state.json"), "{not json");
        StateStore.State corrupt = store.load();
        assertTrue(corrupt.monitors().isEmpty());
    }
}
