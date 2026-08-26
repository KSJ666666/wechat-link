package com.group.autotrip.common.model;

/**
 * 景点信息。
 *
 * @param name        景点名称
 * @param address     地址
 * @param openTime    开放时间
 * @param ticketPrice 门票价格（元，免费为 0）
 * @param brief       简介
 * @param tip         注意事项 / 避坑
 */
public record Spot(
        String name,
        String address,
        String openTime,
        double ticketPrice,
        String brief,
        String tip) {
}
