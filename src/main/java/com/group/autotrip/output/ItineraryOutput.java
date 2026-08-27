package com.group.autotrip.output;

import com.group.autotrip.common.model.BudgetItem;
import com.group.autotrip.common.model.DayPlan;
import com.group.autotrip.common.model.Itinerary;
import com.group.autotrip.common.model.Spot;

import java.util.Locale;

/**
 * 行程单成品排版：从结构化 {@link Itinerary} 渲染适合微信发送的文本（路书与行程单合一）。
 */
public final class ItineraryOutput {

    private ItineraryOutput() {
    }

    public static String render(Itinerary itinerary) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(nonBlank(itinerary.title(), "行程单")).append("】\n");
        if (itinerary.status() != null) {
            sb.append("状态：").append(itinerary.status().label()).append("\n");
        }
        if (itinerary.route() != null) {
            sb.append("路线：").append(nonBlank(itinerary.route().start(), "起点"))
                    .append(" → ").append(nonBlank(itinerary.route().end(), "终点"));
            if (itinerary.route().routeType() != null) {
                sb.append("（").append(itinerary.route().routeType().label()).append("）");
            }
            if (itinerary.route().distanceKm() > 0) {
                sb.append("（约 ").append((int) Math.round(itinerary.route().distanceKm()))
                        .append(" 公里，预计 ").append(itinerary.route().durationMin()).append(" 分钟）");
            }
            sb.append("\n");
        }
        if (itinerary.budget() > 0) {
            sb.append("总预算：").append(formatMoney(itinerary.budget())).append(" 元");
            if (!itinerary.budgetItems().isEmpty()) {
                sb.append("（");
                for (int i = 0; i < itinerary.budgetItems().size(); i++) {
                    BudgetItem item = itinerary.budgetItems().get(i);
                    if (i > 0) {
                        sb.append("；");
                    }
                    sb.append(item.category()).append(" ").append(formatMoney(item.amount()));
                }
                sb.append("）");
            }
            sb.append("\n");
        }
        sb.append("\n");
        for (DayPlan day : itinerary.days()) {
            sb.append("第 ").append(day.day()).append(" 天");
            if (day.date() != null) {
                sb.append("（").append(day.date()).append("）");
            }
            if (!nonBlank(day.stayCity(), "").isEmpty()) {
                sb.append(" · 住宿：").append(day.stayCity());
            }
            sb.append("\n");
            for (Spot spot : day.spots()) {
                sb.append("  - ").append(nonBlank(spot.name(), "（未命名）"));
                if (!spot.openTime().isBlank()) {
                    sb.append("（").append(spot.openTime()).append("）");
                }
                if (spot.ticketPrice() > 0) {
                    sb.append(" 门票 ").append(formatMoney(spot.ticketPrice())).append(" 元");
                }
                sb.append("\n");
                if (!spot.tip().isBlank()) {
                    sb.append("    提示：").append(spot.tip()).append("\n");
                }
            }
            if (!day.note().isBlank()) {
                sb.append("  ").append(day.note()).append("\n");
            }
            sb.append("\n");
        }
        if (!itinerary.notes().isEmpty()) {
            sb.append("出行注意：\n");
            for (String note : itinerary.notes()) {
                sb.append("- ").append(note).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String formatMoney(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
