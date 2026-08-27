package com.group.autotrip.common.model;

/**
 * 路线类型。对应 。
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
    public String label() {
        return label;
    }
}
