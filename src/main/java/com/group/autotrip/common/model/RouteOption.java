package com.group.autotrip.common.model;

/** 某一种交通方式的路线结果。 */
public record RouteOption(
        TransportMode mode,
        String originName,
        String destinationName,
        long distanceMeters,
        long durationSeconds,
        String cost,
        String summary) {
}
