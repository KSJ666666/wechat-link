package com.group.autotrip.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 路线类型。
 */
public enum RouteKind {
    EXPRESSWAY("高速"),
    NATIONAL_ROAD("国道"),
    PROVINCIAL_ROAD("省道"),
    OTHER("其他");

    private final String label;

    RouteKind(String label) {
        this.label = label;
    }

    /** 中文说明 */
    @JsonValue
    public String label() {
        return label;
    }

    /** 从字符串解析（枚举名或中文标签均可），无法识别返回 OTHER */
    @JsonCreator
    public static RouteKind from(String text) {
        if (text == null || text.isBlank()) {
            return OTHER;
        }
        String trimmed = text.trim();
        for (RouteKind kind : values()) {
            if (kind.name().equalsIgnoreCase(trimmed) || kind.label().equals(trimmed)) {
                return kind;
            }
        }
        if (trimmed.contains("高速")) {
            return EXPRESSWAY;
        }
        if (trimmed.contains("国道")) {
            return NATIONAL_ROAD;
        }
        if (trimmed.contains("省道")) {
            return PROVINCIAL_ROAD;
        }
        return OTHER;
    }
}
