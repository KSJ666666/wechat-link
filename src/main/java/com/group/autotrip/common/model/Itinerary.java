package com.group.autotrip.common.model;

import java.util.List;

/**
 * 自驾行程单（最终交付给用户的成品）。
 *
 * @param title       行程标题
 * @param route       关联路线
 * @param days        按天的计划
 * @param budget      总预算（元）
 * @param notes       出行注意事项
 * @param status      行程状态（默认草拟）
 * @param budgetItems 预算明细（默认空列表）
 */
public record Itinerary(
        String title,
        Route route,
        List<DayPlan> days,
        double budget,
        List<String> notes,
        PlanStatus status,
        List<BudgetItem> budgetItems) {

    /** 空列表与空状态兜底，避免调用方拿到 null */
    public Itinerary {
        days = days == null ? List.of() : List.copyOf(days);
        notes = notes == null ? List.of() : List.copyOf(notes);
        budgetItems = budgetItems == null ? List.of() : List.copyOf(budgetItems);
        status = status == null ? PlanStatus.DRAFT : status;
    }
}
