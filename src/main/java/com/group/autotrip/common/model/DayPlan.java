package com.group.autotrip.common.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 单日行程计划。
 *
 * @param day        第几天（从 1 开始）
 * @param date       日期
 * @param spots      当天景点 / 活动列表
 * @param stayCity   当晚住宿城市
 * @param drivingKm  当天驾车里程（公里）
 * @param note       当日说明
 */
public record DayPlan(
        int day,
        LocalDate date,
        List<Spot> spots,
        String stayCity,
        double drivingKm,
        String note) {

    /** 空景点列表兜底，避免调用方拿到 null */
    public DayPlan {
        spots = spots == null ? List.of() : List.copyOf(spots);
    }
}
