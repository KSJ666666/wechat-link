package com.group.autotrip.output;

import com.group.autotrip.common.model.BudgetItem;
import com.group.autotrip.common.model.DayPlan;
import com.group.autotrip.common.model.Itinerary;
import com.group.autotrip.common.model.PlanStatus;
import com.group.autotrip.common.model.Route;
import com.group.autotrip.common.model.RouteKind;
import com.group.autotrip.common.model.Spot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryOutputTest {

    @Test
    void rendersFullItinerary() {
        Itinerary itinerary = new Itinerary(
                "杭州三日自驾",
                new Route("上海", "杭州", List.of(), 180, 150, RouteKind.EXPRESSWAY),
                List.of(new DayPlan(1, LocalDate.of(2026, 8, 28),
                        List.of(new Spot("西湖", "龙井路1号", "00:00-24:00", 0, "著名湖泊", "避开周末")),
                        "杭州", 60, "轻松游")),
                3000,
                List.of("记得带伞", "提前订房"),
                PlanStatus.DRAFT,
                List.of(new BudgetItem("住宿", 1500, "两晚"), new BudgetItem("门票", 600, "")));

        String text = ItineraryOutput.render(itinerary);

        assertTrue(text.contains("杭州三日自驾"));
        assertTrue(text.contains("状态：草拟"));
        assertTrue(text.contains("上海 → 杭州"));
        assertTrue(text.contains("高速"));
        assertTrue(text.contains("总预算：3000 元"));
        assertTrue(text.contains("住宿 1500"));
        assertTrue(text.contains("第 1 天（2026-08-28）"));
        assertTrue(text.contains("西湖"));
        assertTrue(text.contains("避开周末"));
        assertTrue(text.contains("记得带伞"));
    }

    @Test
    void rendersMinimalItineraryWithoutCrash() {
        Itinerary itinerary = new Itinerary(
                "空行程", null, List.of(), 0, List.of(), null, null);
        String text = ItineraryOutput.render(itinerary);
        assertTrue(text.contains("空行程"));
        assertFalse(text.contains("总预算"));
    }
}
