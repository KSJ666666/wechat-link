package com.group.autotrip.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.skill.SkillContext;
import com.group.autotrip.skill.SkillDispatcher;
import com.group.autotrip.tools.CustomTools;
import com.group.autotrip.common.FunctionTool;
import jakarta.annotation.PreDestroy;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼（DashScope）LLM 能力封装：
 * <ul>
 *   <li>chat —— OpenAI 兼容对话接口（qwen 系列）</li>
 * </ul>
 */
@Service
public class DashScopeService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeService.class);

    private static final String BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String CHAT_URL = BASE_URL + "/compatible-mode/v1/chat/completions";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final CustomTools customTools;
    private final SkillDispatcher skillDispatcher;
    private final ExecutorService toolExecutor;

    public DashScopeService(
            CustomTools customTools,
            SkillDispatcher skillDispatcher,
            @Value("${dashscope.tool-execution-mode:parallel}") String toolExecutionMode,
            @Value("${dashscope.tool-execution-threads:4}") int toolExecutionThreads) {
        this.customTools = customTools;
        this.skillDispatcher = skillDispatcher;
        this.toolExecutor = "serial".equalsIgnoreCase(toolExecutionMode)
                ? Executors.newSingleThreadExecutor()
                : Executors.newFixedThreadPool(Math.max(1, toolExecutionThreads));
        log.info("工具执行模式：{}", toolExecutionMode);
    }

    @PreDestroy
    public void close() {
        toolExecutor.shutdownNow();
    }

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Value("${dashscope.chat-model:qwen-plus}")
    private String chatModel;

    @Value("${dashscope.enable-search:true}")
    private boolean enableSearch;

    @Value("${dashscope.forced-search:true}")
    private boolean forcedSearch;

    @Value("${dashscope.search-extension:true}")
    private boolean searchExtension;

    @Value("${dashscope.tool-execution-max-rounds:12}")
    private int maxToolRounds;

    /** 文本对话，返回模型回复内容 */
    public String chat(String userText) throws IOException {
        requireKey();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        if (enableSearch && needsWebSearch(userText) && !looksLikeWeatherQuestion(userText)) {
            addSearchOptions(body);
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userText);

        JsonNode resp = postJson(CHAT_URL, body);
        String reply = resp.path("choices").path(0).path("message").path("content").asText("");
        if (reply.isEmpty()) {
            throw new IOException("对话返回为空: " + resp);
        }
        return reply;
    }

    /**
     * 对话 + 自动判断是否调用工具（函数调用）。
     * 模型会调用天气等工具获取准确数据，否则返回文本回复。
     */
    public ChatResult chatOrGenerate(String userText) throws IOException {
        requireKey();

        Optional<String> skillReply = skillDispatcher.tryExecute(
                new SkillContext(userText, customTools, this));
        if (skillReply.isPresent()) {
            return new ChatResult(skillReply.get());
        }

        // ===== 第一阶段：只带工具、不联网搜索 =====
        // 模型能用工具回答（天气或注册表工具）就走工具，全程不联网搜索。
        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        ArrayNode messages = body.putArray("messages");
        addSystemAndUser(messages, userText);
        addTools(body);
        body.put("tool_choice", "auto");

        JsonNode firstResp = postJson(CHAT_URL, body);
        JsonNode firstMessage = firstResp.path("choices").path(0).path("message");
        JsonNode firstCalls = firstMessage.path("tool_calls");

        // ===== 没有任何工具被调用 = 工具回答不了 =====
        // 第二阶段：联网搜索兜底回答（若配置了 enableSearch）
        if (!firstCalls.isArray() || firstCalls.isEmpty()) {
            if (enableSearch) {
                ObjectNode searchBody = mapper.createObjectNode();
                searchBody.put("model", chatModel);
                addSearchOptions(searchBody);
                ArrayNode searchMessages = searchBody.putArray("messages");
                addSystemAndUser(searchMessages, userText);
                JsonNode resp = postJson(CHAT_URL, searchBody);
                String reply = resp.path("choices").path(0).path("message").path("content").asText("");
                if (reply.isEmpty()) {
                    throw new IOException("对话返回为空: " + resp);
                }
                log.info("工具未命中，已联网搜索回答");
                return new ChatResult(reply);
            }
            String reply = firstMessage.path("content").asText("");
            if (reply.isEmpty()) {
                throw new IOException("对话返回为空: " + firstResp);
            }
            return new ChatResult(reply);
        }

        // ===== 有工具调用 = 多轮闭环（全程不联网搜索） =====
        for (int round = 1; round <= maxToolRounds; round++) {
            JsonNode message;
            if (round == 1) {
                message = firstMessage;   // 复用第一阶段结果，避免重复请求
            } else {
                message = postJson(CHAT_URL, body).path("choices").path(0).path("message");
            }
            JsonNode toolCalls = message.path("tool_calls");

            // 没有工具调用 = 最终文本回复
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String reply = message.path("content").asText("");
                if (reply.isEmpty()) {
                    throw new IOException("对话返回为空");
                }
                return new ChatResult(reply);
            }

            log.info("第 {} 轮：模型请求调用 {} 个工具", round, toolCalls.size());

            // 本程序执行全部工具（串行或并行），结果回传，进入下一轮
            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.set("tool_calls", toolCalls);
            appendToolResults(messages, toolCalls);
        }

        // ===== 达到最大轮数仍未结束 =====
        // 追加一条强制收尾消息，让模型基于已有工具结果直接总结，避免回复失败。
        ObjectNode finalize = messages.addObject();
        finalize.put("role", "user");
        finalize.put("content", "工具调用已结束，请基于已有工具结果直接生成最终回复，不要再调用工具。");
        JsonNode finalResp = postJson(CHAT_URL, body);
        JsonNode finalMessage = finalResp.path("choices").path(0).path("message");
        String finalReply = finalMessage.path("content").asText("");
        if (finalReply.isEmpty()) {
            throw new IOException("对话返回为空");
        }
        return new ChatResult(finalReply);
    }

    /** 组装 system + user 两条消息（工具优先的系统提示） */
    private void addSystemAndUser(ArrayNode messages, String userText) {
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content",
                "你是微信机器人助手。当用户询问某个地点的实时/当前天气时，必须调用 query_weather 工具获取准确天气数据，"
                        + "不要自己编造天气。"
                        + toolRules()
                        + "只要问题能通过上述工具解决，就必须调用对应工具，禁止直接凭训练知识回答或编造数据。"
                        + "信息足够后必须直接生成最终回答，不要继续调用工具；同一轮内可以并行调用多个工具。"
                        + "注意：如果用户询问的是省份、自治区等省级区域（如'河南天气'、'广东省天气'）的天气，不要调用 query_weather 工具，"
                        + "而应提示用户：该查询仅支持具体城市，请提供具体的城市名称（如'郑州'、'广州'）。"
                        + "其他无法用工具回答的问题，系统会自动联网搜索。");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userText);
    }

    /** 注册全部工具：由注册表自动收集（成员新增 FunctionTool 实现类即可自动注册） */
    private void addTools(ObjectNode body) {
        // 一次性创建 tools 数组（注意：putArray 每次调用会替换原数组，所以只能建一次）
        ArrayNode tools = body.putArray("tools");
        for (FunctionTool ft : customTools.all()) {
            tools.add(toolSchema(ft.name(), ft.description(), ft.parameters()));
        }
    }

    /** 从注册表自动生成各执行型工具的调用规则（成员新增工具类后自动生效） */
    private String toolRules() {
        StringBuilder sb = new StringBuilder();
        for (FunctionTool tool : customTools.all()) {
            sb.append("当用户需求符合“").append(tool.description())
                    .append("”时，必须调用 ").append(tool.name())
                    .append(" 工具，禁止凭训练知识编造结果；");
        }
        return sb.toString();
    }

    /** 开启联网搜索（工具回答不了时的兜底） */
    private void addSearchOptions(ObjectNode body) {
        body.put("enable_search", true);
        ObjectNode searchOptions = body.putObject("search_options");
        if (forcedSearch) {
            searchOptions.put("forced_search", true);
        }
        if (searchExtension) {
            searchOptions.put("enable_search_extension", true);
        }
    }

    /** 构造一个 OpenAI 兼容的 function 工具描述（type + name + description + parameters） */
    private ObjectNode toolSchema(String name, String description, JsonNode parameters) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", name);
        fn.put("description", description);
        fn.set("parameters", parameters);
        return tool;
    }

    /** 串行或并行执行一轮工具调用，结果按原始顺序返回 */
    List<String> executeTools(JsonNode toolCalls) throws InterruptedException {
        List<Callable<String>> tasks = new ArrayList<>();
        for (JsonNode tc : toolCalls) {
            String functionName = tc.path("function").path("name").asText("");
            String arguments = tc.path("function").path("arguments").asText("");
            tasks.add(() -> {
                try {
                    JsonNode args = mapper.readTree(arguments);
                    return customTools.execute(functionName, args);
                } catch (Exception e) {
                    return "工具执行失败：" + e.getMessage();
                }
            });
        }
        List<Future<String>> futures = toolExecutor.invokeAll(tasks);
        List<String> results = new ArrayList<>(futures.size());
        for (Future<String> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                results.add("工具执行失败：" + e.getCause().getMessage());
            }
        }
        return results;
    }

    /** 把一轮工具执行结果追加为 role=tool 消息 */
    private void appendToolResults(ArrayNode messages, JsonNode toolCalls) throws IOException {
        List<String> results;
        try {
            results = executeTools(toolCalls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("工具执行被中断", e);
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonNode tc = toolCalls.get(i);
            ObjectNode toolMsg = messages.addObject();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tc.path("id").asText(""));
            toolMsg.put("content", results.get(i));
            log.info("    工具 {} 参数 {} -> {}",
                    tc.path("function").path("name").asText(""),
                    tc.path("function").path("arguments").asText(""),
                    results.get(i));
        }
    }

    /** 实时/热点类关键词：命中则本次对话联网搜索，普通聊天不搜 */
    private static final Set<String> SEARCH_KEYWORDS = Set.of(
            "新闻", "热点", "资讯",
            "限行", "车牌",
            "今天", "明天", "后天", "昨天", "实时", "现在", "当前", "最新", "近期", "最近",
            "农历", "几号", "星期几", "几点");

    private static boolean needsWebSearch(String userText) {
        if (userText == null) {
            return false;
        }
        for (String keyword : SEARCH_KEYWORDS) {
            if (userText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 天气类关键词：命中则走心知天气准确接口，不联网搜索 */
    private static final Set<String> WEATHER_KEYWORDS = Set.of(
            "天气", "气温", "温度", "降雨", "台风", "暴雨", "下雨", "下雪",
            "晴", "多云", "阴", "湿度", "风力", "天气预报");

    private static boolean looksLikeWeatherQuestion(String userText) {
        if (userText == null) {
            return false;
        }
        for (String keyword : WEATHER_KEYWORDS) {
            if (userText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void requireKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置阿里云百炼 API Key：请检查环境变量 DASHSCOPE_API_KEY，或在 application.properties 中设置 dashscope.api-key");
        }
    }

    private JsonNode postJson(String url, ObjectNode body) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json");
        Request request = builder
                .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + text);
            }
            return mapper.readTree(text);
        }
    }

    /** 对话结果：最终文本回复 */
    public record ChatResult(String text) {
    }
}



