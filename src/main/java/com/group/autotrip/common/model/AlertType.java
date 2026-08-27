package com.group.autotrip.common.model;

/**
 * 告警 / 护航提示类型。
 */
public enum AlertType {
    WEATHER("天气"),
    TRAFFIC("路况"),
    TIME("时间"),
    BUDGET("预算"),
    OTHER("其他");

    private final String label;

    AlertType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
