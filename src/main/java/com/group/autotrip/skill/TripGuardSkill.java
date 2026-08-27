package com.group.autotrip.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.autotrip.common.model.AlertType;
import com.group.autotrip.common.model.MonitorTarget;
import com.group.autotrip.agent.MonitorService;
import com.group.autotrip.agent.TripPlanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 行程护航技能：把用户消息解析成监控目标并注册，支持查看与取消。
 *
 * <p>示例：“监控郑州天气，温度低于0度就提醒我”“查看监控”“取消监控”。
 * 通过 {@link ObjectProvider} 懒注入 MonitorService，解开
 * DashScopeService → SkillDispatcher → SkillRegistry → 本技能 → MonitorService → DashScopeService 的构造循环。
 */
@Component
public class TripGuardSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(TripGuardSkill.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> TRIGGER_WORDS = List.of("护航", "监控", "提醒我", "盯着", "帮我盯");
    private static final List<String> LIST_WORDS = List.of("查看", "列表", "有哪些", "看看");
    private static final List<String> CANCEL_WORDS = List.of("取消", "删除", "停止", "关闭", "关掉");

    private static final List<String> WEATHER_HINTS = List.of("天气", "气温", "温度", "降雨", "下雨", "下雪");
    private static final List<String> TRAFFIC_HINTS = List.of("路况", "拥堵", "堵车");
    private static final List<String> TIME_HINTS = List.of("时间", "几点", "出发");
    private static final List<String> BUDGET_HINTS = List.of("预算", "花费", "超支");

    private final ObjectProvider<MonitorService> monitorServiceProvider;
    private final TripPlanStore tripPlanStore;

    public TripGuardSkill(ObjectProvider<MonitorService> monitorServiceProvider, TripPlanStore tripPlanStore) {
        this.monitorServiceProvider = monitorServiceProvider;
        this.tripPlanStore = tripPlanStore;
    }

    @Override
    public String name() {
        return "trip_guard";
    }

    @Override
    public String description() {
        return "处理行程护航消息：注册、查看或取消定时监控（天气低于0度、道路拥堵、时间提醒等）。";
    }

    @Override
    public boolean supports(String userText) {
        return containsAny(userText, TRIGGER_WORDS);
    }

    @Override
    public String execute(String userText, SkillContext ctx) throws Exception {
        MonitorService monitor = monitorServiceProvider.getObject();
        String userId = ctx.userId();

        if (containsAny(userText, CANCEL_WORDS)) {
            int removed = monitor.removeAll(userId);
            return removed > 0
                    ? "已取消全部 " + removed + " 条监控。"
                    : "当前没有进行中的监控。";
        }
        if (containsAny(userText, LIST_WORDS)) {
            return formatList(monitor.list(userId));
        }
        return register(userText, ctx, monitor, userId);
    }

    private String register(String userText, SkillContext ctx, MonitorService monitor, String userId) {
        MonitorTarget target = extractTarget(userText, ctx);
        if (target == null) {
            return "抱歉，没有从你的消息中解析出监控内容。\n"
                    + "示例：监控郑州天气，温度低于0度就提醒我。";
        }
        if (target.type() == AlertType.BUDGET && !tripPlanStore.has(userId)) {
            return "预算监控需要先创建行程。先发送“帮我规划三天杭州行程”生成行程单，再来开启预算监控。";
        }
        if (target.rule().isBlank()) {
            target = new MonitorTarget(target.name(), target.type(), target.keyword(), "每次检查都提醒");
        }
        if (!monitor.add(userId, target)) {
            return "监控“" + target.name() + "”已存在，无需重复添加。\n"
                    + "发送“查看监控”可查看列表，发送“取消监控”可全部取消。";
        }
        return "已开启行程护航：" + target.name() + "\n"
                + "类型：" + target.type().label() + "\n"
                + "规则：" + target.rule() + "\n"
                + "我会定期自动检查，规则触发时第一时间私聊提醒你。\n"
                + "发送“查看监控”查看列表，发送“取消监控”全部取消。";
    }

    private String formatList(List<MonitorTarget> targets) {
        if (targets.isEmpty()) {
            return "当前没有进行中的监控。\n"
                    + "发送“监控郑州天气，低于0度就提醒我”即可开启行程护航。";
        }
        StringBuilder sb = new StringBuilder("当前监控（").append(targets.size()).append(" 条）：\n");
        for (int i = 0; i < targets.size(); i++) {
            MonitorTarget target = targets.get(i);
            sb.append(i + 1).append(". [").append(target.type().label()).append("] ")
                    .append(target.name());
            if (!target.rule().isBlank()) {
                sb.append(" —— ").append(target.rule());
            }
            sb.append('\n');
        }
        sb.append("发送“取消监控”可全部取消。");
        return sb.toString();
    }

    /** 用 LLM 把用户消息拆成监控目标；失败时启发式兜底 */
    private MonitorTarget extractTarget(String userText, SkillContext ctx) {
        try {
            String prompt = "从下面的用户消息中提取行程护航监控信息，只输出 JSON，不要输出其他内容："
                    + "{\"name\":\"监控项名称（简短）\",\"type\":\"WEATHER、TRAFFIC、TIME、BUDGET、OTHER 之一\","
                    + "\"keyword\":\"监控对象（城市名，或“城市 道路”，可为空）\",\"rule\":\"触发规则描述\"}\n"
                    + "用户消息：" + userText;
            String reply = ctx.llm().chat(prompt);
            String json = firstJsonObject(reply);
            if (json != null) {
                JsonNode node = MAPPER.readTree(json);
                AlertType type = parseType(node.path("type").asText(""));
                String name = node.path("name").asText("");
                if (type != null && !name.isBlank()) {
                    return new MonitorTarget(name, type,
                            node.path("keyword").asText(""),
                            node.path("rule").asText(""));
                }
            }
        } catch (Exception e) {
            log.warn("LLM 解析监控目标失败，使用启发式兜底：{}", e.getMessage());
        }
        return heuristicTarget(userText);
    }

    /** LLM 不可用或解析失败时的兜底解析 */
    static MonitorTarget heuristicTarget(String userText) {
        AlertType type = AlertType.OTHER;
        if (containsAny(userText, WEATHER_HINTS)) {
            type = AlertType.WEATHER;
        } else if (containsAny(userText, TRAFFIC_HINTS)) {
            type = AlertType.TRAFFIC;
        } else if (containsAny(userText, TIME_HINTS)) {
            type = AlertType.TIME;
        } else if (containsAny(userText, BUDGET_HINTS)) {
            type = AlertType.BUDGET;
        }
        String name = userText.length() <= 20 ? userText : userText.substring(0, 20) + "…";
        return new MonitorTarget(name, type, userText, userText);
    }

    /** 从 LLM 回复中提取首个 JSON 对象（容忍前后多余文字或代码块标记） */
    static String firstJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static AlertType parseType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (AlertType type : AlertType.values()) {
            if (type.name().equalsIgnoreCase(text.trim()) || type.label().equals(text.trim())) {
                return type;
            }
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
