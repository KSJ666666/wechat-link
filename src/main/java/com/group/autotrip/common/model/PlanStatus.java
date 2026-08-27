package com.group.autotrip.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

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

    @JsonValue
    public String label() {
        return label;
    }

    /** 从字符串解析（枚举名或中文标签均可），无法识别返回 DRAFT */
    @JsonCreator
    public static PlanStatus from(String text) {
        if (text == null || text.isBlank()) {
            return DRAFT;
        }
        String trimmed = text.trim();
        for (PlanStatus status : values()) {
            if (status.name().equalsIgnoreCase(trimmed) || status.label().equals(trimmed)) {
                return status;
            }
        }
        return DRAFT;
    }
}
