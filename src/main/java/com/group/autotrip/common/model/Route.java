package com.group.autotrip.common.model;

import java.util.List;

/**
 * 自驾路线。
 *
 * @param start       起点
 * @param end         终点
 * @param waypoints   途经点列表
 * @param distanceKm  总里程（公里）
 * @param durationMin 预计耗时（分钟）
 * @param routeType   路线类型（高速 / 国道 / 省道）
 */
public record Route(
        String start,
        String end,
        List<String> waypoints,
        double distanceKm,
        int durationMin,
        String routeType) {

    /** 空途经点列表兜底，避免调用方拿到 null */
    public Route {
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }
}
