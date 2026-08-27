package com.group.autotrip.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.agent.DashScopeService;
import com.group.autotrip.common.model.AlertType;
import com.group.autotrip.common.model.MonitorTarget;
import com.group.autotrip.tools.CustomTools;
import com.group.autotrip.wechat.WeChatService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 行程护航：按用户维护监控目标注册表，定时巡检，规则触发时通过微信主动推送告警。
 *
 * <p>巡检数据按类型取自已注册工具（WEATHER→query_weather、TRAFFIC→query_traffic、TIME→当前时刻），
 * 规则是否触发由 LLM 判断，自然语言规则无需硬编码阈值。监控列表为内存态，重启后清空。
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm（EEEE）", Locale.CHINA);

    private final CustomTools tools;
    private final DashScopeService llm;
    private final WeChatService weChat;

    /** userId（null 归入空串桶）→ 该用户的监控列表 */
    private final Map<String, List<MonitorTarget>> targetsByUser = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Value("${monitor.enabled:true}")
    private boolean enabled;

    @Value("${monitor.check-interval-minutes:30}")
    private int checkIntervalMinutes;

    public MonitorService(CustomTools tools, DashScopeService llm, WeChatService weChat) {
        this.tools = tools;
        this.llm = llm;
        this.weChat = weChat;
    }

    // ===== 注册表 =====

    /** 注册一条监控；同用户下同名监控已存在时返回 false */
    public synchronized boolean add(String userId, MonitorTarget target) {
        List<MonitorTarget> list = targetsByUser.computeIfAbsent(
                keyOf(userId), k -> new CopyOnWriteArrayList<>());
        boolean exists = list.stream().anyMatch(t -> t.name().equals(target.name()));
        if (exists) {
            return false;
        }
        list.add(target);
        return true;
    }

    /** 清空某用户的全部监控，返回删除条数 */
    public synchronized int removeAll(String userId) {
        List<MonitorTarget> removed = targetsByUser.remove(keyOf(userId));
        return removed == null ? 0 : removed.size();
    }

    /** 某用户当前监控列表（按注册顺序） */
    public List<MonitorTarget> list(String userId) {
        return List.copyOf(targetsByUser.getOrDefault(keyOf(userId), List.of()));
    }

    /** 全部用户监控总条数 */
    public int size() {
        return targetsByUser.values().stream().mapToInt(List::size).sum();
    }

    // ===== 定时巡检 =====

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("行程护航已关闭（monitor.enabled=false）");
            return;
        }
        int minutes = Math.max(1, checkIntervalMinutes);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "trip-guard-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::checkAllSafely, 1, minutes, TimeUnit.MINUTES);
        log.info("行程护航巡检已启动：每 {} 分钟检查一次", minutes);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void checkAllSafely() {
        try {
            checkAll();
        } catch (Exception e) {
            log.warn("行程护航巡检异常：{}", e.getMessage());
        }
    }

    /** 巡检全部用户的全部监控；单条失败不影响其他 */
    public void checkAll() {
        List<Map.Entry<String, List<MonitorTarget>>> snapshot =
                new ArrayList<>(targetsByUser.entrySet());
        if (snapshot.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<MonitorTarget>> entry : snapshot) {
            for (MonitorTarget target : List.copyOf(entry.getValue())) {
                try {
                    checkOne(entry.getKey(), target);
                } catch (Exception e) {
                    log.warn("监控“{}”检查失败：{}", target.name(), e.getMessage());
                }
            }
        }
    }

    private void checkOne(String userId, MonitorTarget target) throws Exception {
        String data = collectData(target);
        if (data == null) {
            return; // 本期不支持的类型（如 BUDGET）静默跳过
        }
        if (!judge(target.rule(), data)) {
            log.debug("监控未触发：{}，数据：{}", target.name(), data);
            return;
        }
        log.info("监控触发：{}（{}），规则：{}，数据：{}",
                target.name(), target.type().label(), target.rule(), data);
        pushAlert(userId, target, data);
    }

    /** 按监控类型取当前数据；返回 null 表示该类型本期不巡检 */
    private String collectData(MonitorTarget target) throws Exception {
        return switch (target.type()) {
            case WEATHER -> {
                ObjectNode args = MAPPER.createObjectNode();
                args.put("location", target.keyword());
                yield tools.execute("query_weather", args);
            }
            case TRAFFIC -> {
                String[] cityAndRoad = parseCityAndRoad(target.keyword());
                if (cityAndRoad == null) {
                    yield "路况数据暂不可用：未能从监控关键字中解析出城市与道路（"
                            + target.keyword() + "），注册时请写成“城市 道路”格式";
                }
                ObjectNode args = MAPPER.createObjectNode();
                args.put("city", cityAndRoad[0]);
                args.put("road", cityAndRoad[1]);
                yield tools.execute("query_traffic", args);
            }
            case TIME, OTHER -> "时刻：" + LocalDateTime.now().format(TIME_FORMAT);
            default -> null;
        };
    }

    /** LLM 裁判：规则 + 观测数据 → 是否触发 */
    private boolean judge(String rule, String data) throws Exception {
        String prompt = "你是行程护航监控的触发判断器。请只根据下面的规则和观测数据判断规则是否被触发。\n"
                + "规则：" + rule + "\n"
                + "观测数据：" + data + "\n"
                + "只输出一个汉字「是」或「否」，不要输出任何其他内容。";
        return parseYesNo(llm.chat(prompt));
    }

    /** 解析裁判结果：含“否”→不触发；含“是”→触发；无法解析→不触发（宁可不打扰） */
    static boolean parseYesNo(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String trimmed = answer.trim();
        if (trimmed.contains("否")) {
            return false;
        }
        return trimmed.contains("是");
    }

    /** 从“郑州 北四环中路”这类关键字中解析城市与道路，解析不出返回 null */
    static String[] parseCityAndRoad(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String[] parts = keyword.trim().split("[\\s,，、]+");
        return parts.length >= 2 ? new String[]{parts[0], parts[1]} : null;
    }

    /** 触发后的微信主动推送；未登录或推送失败只记日志，不影响巡检 */
    private void pushAlert(String userId, MonitorTarget target, String data) {
        String text = "【行程护航】" + target.name() + " 触发告警\n"
                + "观测数据：" + data + "\n"
                + "触发规则：" + target.rule();
        if (userId == null || userId.isBlank()) {
            log.info("告警无接收用户（非微信场景），仅记录：{}", text.replace('\n', ' '));
            return;
        }
        if (!weChat.isLoggedIn()) {
            log.warn("微信未登录，告警无法推送：{}", text.replace('\n', ' '));
            return;
        }
        try {
            weChat.sendText(userId, text);
        } catch (Exception e) {
            log.warn("告警推送失败（用户 {}）：{}", userId, e.getMessage());
        }
    }

    private static String keyOf(String userId) {
        return userId == null ? "" : userId;
    }
}
