package com.group.autotrip.common.model;

/**
 * 定时护航监控目标（纯数据）。
 *
 * @param name    监控项名称，如“郑州 明天天气”
 * @param type    告警类型
 * @param keyword 匹配关键词 / 监控关键字
 * @param rule    监控规则描述，如“当温度降至 0℃ 以下时告警”
 */
public record MonitorTarget(
        String name,
        AlertType type,
        String keyword,
        String rule) {

    /** 空规则兜底，避免调用方拿到 null */
    public MonitorTarget {
        rule = rule == null ? "" : rule;
    }
}
