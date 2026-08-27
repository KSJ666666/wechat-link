package com.group.autotrip.common.model;

/**
 * 行程 / 计划状态。
 */
public enum PlanStatus {
    DRAFT("草拟"),
    CONFIRMED("已确认"),
    ONGOING("进行中"),
    FINISHED("已完成"),
    CANCELLED("已取消");

    private final String label;

    PlanStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
