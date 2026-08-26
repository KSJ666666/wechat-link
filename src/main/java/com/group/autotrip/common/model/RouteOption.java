package com.group.autotrip.common.model;

/**
 * 某一种交通方式的路线结果。
 *
 * @param mode            交通方式
 * @param originName      起点名称
 * @param destinationName 终点名称
 * @param distanceMeters  路线距离（米）
 * @param durationSeconds 预计耗时（秒）
 * @param cost            费用（元），无费用为空
 * @param summary         该方式的简要说明，如“地铁 1号线”“高铁 G1”
 */
public record RouteOption(
        TransportMode mode,
        String originName,
        String destinationName,
        long distanceMeters,
        long durationSeconds,
        String cost,
        String summary) {
}
