package com.group.autotrip.common.model;

/**
 * 预算明细项（纯数据）。
 *
 * @param category 费用类别，如油费 / 过路费 / 住宿 / 门票 / 餐饮
 * @param amount   金额（元）
 * @param note     备注
 */
public record BudgetItem(
        String category,
        double amount,
        String note) {
}
