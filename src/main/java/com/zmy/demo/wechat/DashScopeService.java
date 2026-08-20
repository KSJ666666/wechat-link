package com.zmy.demo.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zmy.demo.tools.CustomTools;
import com.zmy.demo.tools.FunctionTool;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼（DashScope）LLM 能力封装：
 * <ul>
 *   <li>chat —— OpenAI 兼容对话接口（qwen 系列）</li>
 *   <li>generateImage —— wanx 文生图（异步任务，轮询结果后下载图片字节）</li>
 * </ul>
 */
@Service
public class DashScopeService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeService.class);

    private static final String BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String CHAT_URL = BASE_URL + "/compatible-mode/v1/chat/completions";
    private static final String IMAGE_CREATE_URL = BASE_URL + "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_URL = BASE_URL + "/api/v1/tasks/";
    private static final String TTS_URL = BASE_URL + "/api/v1/services/audio/tts/SpeechSynthesizer";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final CustomTools customTools;

    public DashScopeService(CustomTools customTools) {
        this.customTools = customTools;
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

    @Value("${dashscope.image-model:wanx2.1-t2i-turbo}")
    private String imageModel;

    @Value("${dashscope.image-size:1024*1024}")
    private String imageSize;

    @Value("${dashscope.vision-model:qwen-vl-plus}")
    private String visionModel;

    @Value("${dashscope.tts-model:qwen-audio-3.0-tts-flash}")
    private String ttsModel;

    @Value("${dashscope.tts-voice:longanhuan_v3.6}")
    private String ttsVoice;

    /** 文本对话，返回模型回复内容 */
    public String chat(String userText) throws IOException {
        requireKey();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        if (enableSearch && needsWebSearch(userText) && !looksLikeWeatherQuestion(userText)) {
            body.put("enable_search", true);
            ObjectNode searchOptions = body.putObject("search_options");
            if (forcedSearch) {
                searchOptions.put("forced_search", true);
            }
            if (searchExtension) {
                searchOptions.put("enable_search_extension", true);
            }
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
     * 对话 + 自动判断是否生成图片（函数调用）。
     * 用户要求画图时，模型会调用 generate_image 工具，返回图片提示词；否则返回文本回复。
     */
    public ChatResult chatOrGenerate(String userText) throws IOException {
        requireKey();

        // ===== 第一阶段：只带工具、不联网搜索 =====
        // 模型能用工具回答（计算/时间/天气/股票/画图/语音）就走工具，全程不联网搜索。
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
                return new ChatResult(reply, null, null, null);
            }
            String reply = firstMessage.path("content").asText("");
            if (reply.isEmpty()) {
                throw new IOException("对话返回为空: " + firstResp);
            }
            return new ChatResult(reply, null, null, null);
        }

        // ===== 有工具调用 = 多轮闭环（全程不联网搜索） =====
        for (int round = 1; round <= 5; round++) {
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
                return new ChatResult(reply, null, null, null);
            }

            log.info("第 {} 轮：模型请求调用 {} 个工具", round, toolCalls.size());

            // 透传型工具：解析参数后直接交给上层处理（画图/语音/天气）
            for (JsonNode tc : toolCalls) {
                String functionName = tc.path("function").path("name").asText("");
                String arguments = tc.path("function").path("arguments").asText("");
                if ("generate_image".equals(functionName)) {
                    String prompt = extractArgument(arguments, "prompt");
                    if (prompt != null && !prompt.isBlank()) {
                        log.info("    透传工具 {} 参数 {}", functionName, arguments);
                        return new ChatResult(null, prompt, null, null);
                    }
                }
                if ("speak_text".equals(functionName)) {
                    String speech = extractArgument(arguments, "text");
                    if (speech != null && !speech.isBlank()) {
                        log.info("    透传工具 {} 参数 {}", functionName, arguments);
                        return new ChatResult(null, null, speech, null);
                    }
                }
                if ("query_weather".equals(functionName)) {
                    String location = extractArgument(arguments, "location");
                    if (location != null && !location.isBlank()) {
                        log.info("    透传工具 {} 参数 {}", functionName, arguments);
                        return new ChatResult(null, null, null, location);
                    }
                }
            }

            // 执行型工具（calculate/get_time/get_stock）：本程序执行，结果回传，进入下一轮
            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.set("tool_calls", toolCalls);
            for (JsonNode tc : toolCalls) {
                String toolCallId = tc.path("id").asText("");
                String functionName = tc.path("function").path("name").asText("");
                String arguments = tc.path("function").path("arguments").asText("");

                String result;
                try {
                    result = customTools.execute(functionName, mapper.readTree(arguments));
                } catch (Exception e) {
                    result = "工具执行失败：" + e.getMessage();
                }

                log.info("    工具 {} 参数 {} -> {}", functionName, arguments, result);
                ObjectNode toolMsg = messages.addObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("content", result);
            }
        }
        throw new IOException("工具调用超过 5 轮仍未得到最终回复");
    }

    /** 组装 system + user 两条消息（工具优先的系统提示） */
    private void addSystemAndUser(ArrayNode messages, String userText) {
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content",
                "你是微信机器人助手。当用户要求生成、绘制、创作图片时，必须调用 generate_image 工具；"
                        + "当用户要求语音播报、语音回复、把文字读出来时，必须调用 speak_text 工具；"
                        + "当用户询问某个地点的实时/当前天气时，必须调用 query_weather 工具获取准确天气数据，"
                        + "不要自己编造天气。"
                        + toolRules()
                        + "只要问题能通过上述工具解决，就必须调用对应工具，禁止直接凭训练知识回答或编造数据。"
                        + "注意：如果用户询问的是省份、自治区等省级区域（如'河南天气'、'广东省天气'）的天气，不要调用 query_weather 工具，"
                        + "而应提示用户：该查询仅支持具体城市，请提供具体的城市名称（如'郑州'、'广州'）。"
                        + "绝对不要用文字回答'无法生成图片'或'无法播放语音'。其他无法用工具回答的问题，系统会自动联网搜索。");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userText);
    }

    /** 注册全部工具（generate_image/speak_text/query_weather 透传型，calculate/get_time/get_stock 执行型） */
    private void addTools(ObjectNode body) {
        // 一次性创建 tools 数组（注意：putArray 每次调用会替换原数组，所以只能建一次）
        ArrayNode tools = body.putArray("tools");

        // 工具 1：generate_image（透传型：解析出参数后交给上层画图）
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", "generate_image");
        fn.put("description", "生成一张图片（文生图）。当用户要求画图、生成图片、创作插图/海报等时调用。");
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode promptProp = params.putObject("properties").putObject("prompt");
        promptProp.put("type", "string");
        promptProp.put("description", "图片内容的详细描述（中文）");
        params.putArray("required").add("prompt");

        // 工具 2：speak_text（透传型）
        ObjectNode tool2 = tools.addObject();
        tool2.put("type", "function");
        ObjectNode fn2 = tool2.putObject("function");
        fn2.put("name", "speak_text");
        fn2.put("description", "把文字用语音播报/朗读出来，用于语音回复。当用户要求语音回复、播放语音、读出文字时调用。");
        ObjectNode params2 = fn2.putObject("parameters");
        params2.put("type", "object");
        ObjectNode textProp = params2.putObject("properties").putObject("text");
        textProp.put("type", "string");
        textProp.put("description", "要说的话（中文）");
        params2.putArray("required").add("text");

        // 工具 3：query_weather（透传型）
        ObjectNode tool3 = tools.addObject();
        tool3.put("type", "function");
        ObjectNode fn3 = tool3.putObject("function");
        fn3.put("name", "query_weather");
        fn3.put("description", "查询某个城市的实时天气（当前天气情况）。当用户询问某城市的当前或实时天气时调用，例如'郑州天气怎么样'、'上海现在热吗'。仅支持具体城市，不支持省份、自治区等省级区域——用户询问省级天气时不要调用本工具，应提示用户提供具体城市名。");
        ObjectNode params3 = fn3.putObject("parameters");
        params3.put("type", "object");
        ObjectNode locProp = params3.putObject("properties").putObject("location");
        locProp.put("type", "string");
        locProp.put("description", "地点：必须是具体城市名（如：郑州、上海）或城市拼音（如：zhengzhou），不能是省份、自治区等省级区域");
        params3.putArray("required").add("location");

        // 执行型工具：由注册表自动收集（成员新增 FunctionTool 实现类即可自动注册）
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

    /**
     * 完整的 Function Calling 闭环（教学示例：calculate / get_time 两个自定义工具）。
     *
     * <p>与 {@link #chatOrGenerate} 的区别：这里不只是在模型返回 tool_calls 后把参数拆出来，
     * 而是真正执行工具，并把执行结果以 role=tool 的消息回传给模型，让模型基于结果生成最终回复。
     * 流程：
     * <ol>
     *   <li>把两个工具的 JSON Schema 随 messages 一起发给模型；</li>
     *   <li>模型返回 tool_calls（工具名 + JSON 参数）→ 本程序执行 CustomTools；</li>
     *   <li>把 assistant 的 tool_calls 和每个工具的执行结果追加进消息历史；</li>
     *   <li>再发一次请求，直到模型给出纯文本最终回复（最多 5 轮，防止死循环）。</li>
     * </ol>
     */
    public String chatWithTools(String userText) throws IOException {
        requireKey();

        // ---------- 1. 工具列表由注册表 CustomTools 自动收集（新增 FunctionTool 实现类即可） ----------
        // ---------- 2. 组装请求：model + messages + tools + tool_choice ----------
        ArrayNode messages = mapper.createObjectNode().putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userText);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", chatModel);
        body.set("messages", messages);
        ArrayNode tools = body.putArray("tools");
        for (FunctionTool tool : customTools.all()) {
            tools.add(toolSchema(tool.name(), tool.description(), tool.parameters()));
        }
        body.put("tool_choice", "auto"); // auto=让模型自己决定是否调用工具

        // ---------- 3. 多轮闭环：模型要工具→我们执行→结果回传，直到模型直接给文本 ----------
        for (int round = 1; round <= 5; round++) {
            JsonNode resp = postJson(CHAT_URL, body);
            JsonNode message = resp.path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");

            // 没有工具调用 = 模型给出了最终文本回复
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String reply = message.path("content").asText("");
                if (reply.isEmpty()) {
                    throw new IOException("对话返回为空: " + resp);
                }
                return reply;
            }

            log.info("第 {} 轮：模型请求调用 {} 个工具", round, toolCalls.size());

            // 3a. 把 assistant 的 tool_calls 原样追加进消息历史（content 可以为空）
            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.set("tool_calls", toolCalls);

            // 3b. 逐个执行工具，每个结果追加一条 role=tool 消息（tool_call_id 必须对应）
            for (JsonNode tc : toolCalls) {
                String toolCallId = tc.path("id").asText("");
                String functionName = tc.path("function").path("name").asText("");
                String arguments = tc.path("function").path("arguments").asText("");

                String result;
                try {
                    result = customTools.execute(functionName, mapper.readTree(arguments));
                } catch (Exception e) {
                    // 工具失败也要把错误信息回传给模型，让它如实向用户说明
                    result = "工具执行失败：" + e.getMessage();
                }
                log.info("    工具 {} 参数 {} -> {}", functionName, arguments, result);

                ObjectNode toolMsg = messages.addObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("content", result);
            }
        }
        throw new IOException("工具调用超过 5 轮仍未得到最终回复");
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


    /** 文生图，返回图片字节与文件名 */
    public ImageResult generateImage(String prompt) throws IOException, InterruptedException {
        requireKey();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", imageModel);
        body.putObject("input").put("prompt", prompt);
        ObjectNode params = body.putObject("parameters");
        params.put("size", imageSize);
        params.put("n", 1);

        JsonNode createResp = postJson(IMAGE_CREATE_URL, body, "X-DashScope-Async", "enable");
        String taskId = createResp.path("output").path("task_id").asText("");
        if (taskId.isEmpty()) {
            throw new IOException("文生图任务提交失败: " + createResp);
        }
        log.info("文生图任务已提交，taskId = {}", taskId);

        // 每 3 秒轮询一次，最多约 3 分钟
        String imageUrl = null;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(3000);
            JsonNode task = getJson(TASK_URL + taskId);
            String status = task.path("output").path("task_status").asText("");
            if ("SUCCEEDED".equals(status)) {
                imageUrl = task.path("output").path("results").path(0).path("url").asText(null);
                break;
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "CANCELLED".equals(status)) {
                throw new IOException("文生图任务失败: " + task);
            }
        }
        if (imageUrl == null) {
            throw new IOException("文生图任务超时");
        }

        byte[] bytes = download(imageUrl);
        return new ImageResult(bytes, "image" + extensionOf(imageUrl));
    }

    /** 图片理解：调用视觉模型识别图片内容，返回文字描述 */
    public String describeImage(byte[] imageBytes, String fileName, String prompt) throws IOException {
        requireKey();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", visionModel);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");

        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        imagePart.putObject("image_url").put("url", dataUrlOf(imageBytes, fileName));

        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt == null || prompt.isBlank() ? "请详细描述这张图片的内容" : prompt);

        JsonNode resp = postJson(CHAT_URL, body);
        String reply = resp.path("choices").path(0).path("message").path("content").asText("");
        if (reply.isEmpty()) {
            throw new IOException("图片识别返回为空: " + resp);
        }
        return reply;
    }

    /** 语音合成：TTS 生成 mp3 音频（用于作为文件发送，安全稳妥） */
    public VoiceResult synthesizeVoice(String text) throws IOException {
        requireKey();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", ttsModel);
        ObjectNode input = body.putObject("input");
        input.put("text", text);
        input.put("voice", ttsVoice);
        input.put("format", "mp3");

        JsonNode resp = postJson(TTS_URL, body);
        String audioUrl = resp.path("output").path("audio").path("url").asText("");
        if (audioUrl.isEmpty()) {
            throw new IOException("语音合成失败: " + resp);
        }
        byte[] mp3 = download(audioUrl);
        log.info("语音合成完成（{} 字节）", mp3.length);
        return new VoiceResult(mp3, "reply.mp3");
    }

    private static String extractArgument(String arguments, String key) {
        try {
            return new ObjectMapper().readTree(arguments).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String dataUrlOf(byte[] bytes, String fileName) {
        return "data:" + mimeOf(fileName) + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static String mimeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    /** 实时/热点类关键词：命中则本次对话联网搜索，普通聊天不搜 */
    private static final Set<String> SEARCH_KEYWORDS = Set.of(
            "新闻", "热点", "资讯",
            "股票", "股价", "大盘", "行情", "沪指", "深指", "纳斯达克",
            "汇率", "美元", "欧元", "日元", "英镑",
            "油价", "汽油", "柴油",
            "金价", "黄金", "银价",
            "彩票", "双色球", "大乐透", "开奖",
            "限行", "车牌",
            "赛事", "比赛", "比分", "赛程", "排名", "英超", "西甲", "NBA", "世界杯",
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
        return postJson(url, body, null, null);
    }

    private JsonNode postJson(String url, ObjectNode body, String extraHeader, String extraHeaderValue) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json");
        if (extraHeader != null && extraHeaderValue != null) {
            builder.addHeader(extraHeader, extraHeaderValue);
        }
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

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + text);
            }
            return mapper.readTree(text);
        }
    }

    private byte[] download(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载失败 HTTP " + response.code());
            }
            return response.body() != null ? response.body().bytes() : new byte[0];
        }
    }

    private static String extensionOf(String url) {
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String ext = path.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{2,5}")) {
                return ext;
            }
        }
        return ".png";
    }

    /** 文生图结果 */
    public record ImageResult(byte[] bytes, String fileName) {
    }

    /** 对话结果：文本回复 / 图片生成提示词 / 语音播报文字 / 天气查询地点（四选一） */
    public record ChatResult(String text, String imagePrompt, String speechText, String weatherLocation) {
        public boolean wantsImage() {
            return imagePrompt != null && !imagePrompt.isBlank();
        }

        public boolean wantsSpeech() {
            return speechText != null && !speechText.isBlank();
        }

        public boolean wantsWeather() {
            return weatherLocation != null && !weatherLocation.isBlank();
        }
    }

    /** 语音合成结果（mp3 文件） */
    public record VoiceResult(byte[] bytes, String fileName) {
    }
}



