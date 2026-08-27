package com.group.autotrip.output;

import java.util.List;

/** 旅行规划的成品输出格式化器。 */
public final class TripPlanOutput {

    private TripPlanOutput() {
    }

    public static String render(String origin, String destination, int days, String style,
                                String weatherNow, String forecast, String route,
                                String attractions, List<String> attractionDetails,
                                String llmPlan, List<String> tips) {
        StringBuilder sb = new StringBuilder();
        sb.append("【自驾旅行规划】\n");
        sb.append("出发地：").append(nonBlank(origin, "待补充")).append("\n");
        sb.append("目的地：").append(nonBlank(destination, "待补充")).append("\n");
        sb.append("天数：").append(Math.max(1, days)).append("天\n");
        if (nonBlank(style, null) != null) {
            sb.append("偏好：").append(style).append("\n");
        }

        appendSection(sb, "实时天气", weatherNow);
        appendSection(sb, "天气预报", forecast);
        appendSection(sb, "路线推荐", route);
        appendSection(sb, "景点列表", attractions);

        if (!attractionDetails.isEmpty()) {
            sb.append("重点景点详情\n");
            for (String detail : attractionDetails) {
                sb.append("- ").append(detail).append("\n");
            }
            sb.append("\n");
        }

        appendSection(sb, "路书草案", llmPlan);

        sb.append("执行建议\n");
        for (String tip : tips) {
            sb.append("- ").append(tip).append("\n");
        }

        sb.append("\n如果你愿意，我可以继续按“每天怎么走、住哪里、先玩哪里”再细化一版。");
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        sb.append(title).append("\n");
        sb.append(content.trim()).append("\n\n");
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
