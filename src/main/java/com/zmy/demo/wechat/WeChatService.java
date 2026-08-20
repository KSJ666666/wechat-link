package com.zmy.demo.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.zmy.demo.weather.WeatherService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封装微信 iLink SDK：二维码登录、登录态本地持久化、自动接收消息、发送文本/图片消息。
 *
 * <p>登录后 SDK 心跳会按配置间隔自动调用 getUpdates 拉取消息，
 * 通过 onMessage 监听器回调。收到文本消息后异步调用阿里云百炼 LLM 自动回复：
 * <ul>
 *   <li>普通文本 —— 对话生成文本回复（sendText，已开启联网搜索）</li>
 *   <li>「画 xxx」或「图片 xxx」 —— 文生图后发送图片（sendImage）</li>
 *   <li>「说 xxx」或「语音 xxx」 —— TTS 生成 mp3 语音文件发送（sendFile）</li>
 *   <li>收到语音 —— 直接读取服务端转写的文字并回复</li>
 *   <li>收到图片 —— 用视觉模型识别后回复；若同一会话短时间内还收到一条文字
 *       （图文/文图），把文字作为意图与图片合并识别</li>
 * </ul>
 *
 *
 *
 */
@Service
public class WeChatService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeChatService.class);

    /** 内存中最多保留最近收到的消息条数 */
    private static final int MAX_RECEIVED_MESSAGES = 100;

    /** 图文合并窗口：图片等待文字的最长时间（毫秒）。
     *  需大于心跳轮询间隔（5000ms），才能覆盖"图片先到、文字下一次轮询才到"的跨批到达 */
    private static final long IMAGE_WAIT_TEXT_WINDOW_MS = 8000;

    /** 文图合并窗口：文字等待图片的最长时间（毫秒）。保持较短，避免拖慢普通聊天回复 */
    private static final long TEXT_WAIT_IMAGE_WINDOW_MS = 2000;

    /** 图文/文图配对器：把同一用户短时间内先后到达的图片和文字合并成一次视觉识别 */
    private final ImageTextMerger imageTextMerger = new ImageTextMerger(
            IMAGE_WAIT_TEXT_WINDOW_MS,
            TEXT_WAIT_IMAGE_WINDOW_MS,
            new ImageTextMerger.Handler() {
                @Override
                public void onImage(String fromUserId, MessageItem imageItem, String text) {
                    if (text != null && !text.isBlank()) {
                        log.info("图文/文图合并：from={}，文字意图={}", fromUserId, text);
                    }
                    replyImageAsync(fromUserId, imageItem, text);
                }

                @Override
                public void onText(String fromUserId, String text) {
                    replyAsync(fromUserId, text);
                }
            });

    private final DashScopeService dashScopeService;
    private final WeatherService weatherService;
    private final ObjectMapper stateMapper = new ObjectMapper();
    private final List<ReceivedMessage> receivedMessages = new CopyOnWriteArrayList<>();
    private final ExecutorService replyExecutor = Executors.newSingleThreadExecutor();

    @Value("${wechat.auto-login:true}")
    private boolean autoLogin;

    @Value("${wechat.resume-file:${user.home}/.wechat-demo-resume.json}")
    private String resumeFile;

    private volatile ILinkClient client;

    public WeChatService(DashScopeService dashScopeService, WeatherService weatherService) {
        this.dashScopeService = dashScopeService;
        this.weatherService = weatherService;
    }

    /**
     * 获取登录二维码并开始登录轮询；已登录时直接返回当前二维码。
     *
     * @return 二维码内容（SDK 返回 qrcode_img_content，通常可直接打开为登录链接）
     */
    public synchronized String login() {
        if (client == null) {
            client = createClient();
        }
        if (client.isLoggedIn()) {
            return client.getQrcode();
        }
        String qrCode = client.executeLogin();
        log.info("已获取微信登录二维码，等待扫码登录");
        return qrCode;
    }

    private ILinkClient createClient() {
        ResumeContext resume = loadResume();
        ILinkClientBuilder builder = ILinkClient.builder()
                .config(ILinkConfig.builder()
                        // 登录后每 5 秒自动轮询一次 getUpdates 并触发 onMessage 监听器
                        .heartbeatIntervalMs(5000)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        log.info("微信登录成功，botId = {}", context.getBotId());
                        saveState();
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        // 应用关闭时 SDK 会取消登录轮询，属于正常流程，不作为错误输出
                        if (throwable instanceof CancellationException) {
                            log.info("微信登录已取消");
                            return;
                        }
                        log.error("微信登录失败: {}", throwable.getMessage(), throwable);
                    }
                })
                .onMessage(messages -> {
                    List<ReceivedMessage> batch = new ArrayList<>();
                    for (WeixinMessage message : messages) {
                        ReceivedMessage received = ReceivedMessage.from(message);
                        batch.add(received);
                        MessageItem imageItem = findImageItem(message);
                        log.info("收到微信消息: from={}, 图片={}, 文字={}",
                                received.fromUserId(), imageItem != null, received.text());
                        if (received.fromUserId() == null) {
                            continue;
                        }
                        if (imageItem != null) {
                            // 图片消息 —— 与文字（同一条消息或合并窗口内的另一条）合并后识别回复
                            imageTextMerger.handleImage(received.fromUserId(), imageItem, findText(message));
                            continue;
                        }
                        VoiceItem voiceItem = findVoiceItem(message);
                        if (voiceItem != null) {
                            // 语音消息 —— 服务端已把语音转成文字，直接取 text 走 LLM 回复
                            String voiceText = voiceItem.getText();
                            if (voiceText != null && !voiceText.isBlank()) {
                                log.info("收到语音(已转文字): from={}, text={}", received.fromUserId(), voiceText);
                                replyAsync(received.fromUserId(), voiceText);
                            } else {
                                log.warn("收到语音但未携带转写文字，from={}", received.fromUserId());
                            }
                            continue;
                        }
                        if (received.text() != null && !received.text().isBlank()) {
                            // 文本消息 —— 先进合并窗口等待可能紧随的图片（文图），超时按普通对话回复
                            imageTextMerger.handleText(received.fromUserId(), received.text());
                        }
                    }
                    receivedMessages.addAll(batch);
                    trimReceivedMessages();
                    // 消息游标已更新，保存登录态，避免重启后重复拉取
                    saveState();
                });
        if (resume != null) {
            builder.resumeContext(resume);
            log.info("已加载本地登录状态文件 {}", resumeFile);
        }
        return builder.build();
    }

    private void trimReceivedMessages() {
        int overflow = receivedMessages.size() - MAX_RECEIVED_MESSAGES;
        if (overflow > 0) {
            receivedMessages.subList(0, overflow).clear();
        }
    }

    private static MessageItem findImageItem(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item.getImage_item() != null) {
                return item;
            }
        }
        return null;
    }

    private static VoiceItem findVoiceItem(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item.getVoice_item() != null) {
                return item.getVoice_item();
            }
        }
        return null;
    }

    private static String findText(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    private void replyImageAsync(String toUserId, MessageItem imageItem, String prompt) {
        replyExecutor.submit(() -> {
            try {
                requireClient();
                byte[] imageBytes = client.downloadImageFromMessageItem(imageItem);
                String description = dashScopeService.describeImage(
                        imageBytes, "image.jpg",
                        prompt == null || prompt.isBlank()
                                ? "请详细描述这张图片的内容，如果图中有文字请一并识别出来"
                                : prompt);
                requireClient();
                client.sendText(toUserId, description);
                log.info("已向 {} 发送图片识别结果（{} 字节）", toUserId, imageBytes.length);
            } catch (Exception e) {
                log.error("图片识别回复失败: {}", e.getMessage(), e);
                sendTextBestEffort(toUserId, "图片识别失败：" + e.getMessage());
            }
        });
    }

    private void replyAsync(String toUserId, String text) {
        replyExecutor.submit(() -> {
            try {
                handleReply(toUserId, text);
            } catch (Exception e) {
                log.error("LLM 自动回复失败: {}", e.getMessage(), e);
                sendTextBestEffort(toUserId, "回复失败：" + e.getMessage());
            }
        });
    }

    private void handleReply(String toUserId, String text) throws Exception {
        String trimmed = text.trim();

        // 快路径：明确以「画/图片」开头，直接出图
        if (trimmed.startsWith("画") || trimmed.startsWith("图片")) {
            String prompt = trimmed.replaceFirst("^(画|图片)[：:\\s]*", "");
            if (prompt.isEmpty()) {
                sendTextBestEffort(toUserId, "请描述想生成的图片，例如：画 一只猫在月球上");
                return;
            }
            sendImageReply(toUserId, prompt);
            return;
        }

        // 快路径：「说/语音」—— TTS 生成 mp3 文件发送
        if (trimmed.startsWith("说") || trimmed.startsWith("语音")) {
            String speech = trimmed.replaceFirst("^(说|语音)[：:\\s]*", "");
            if (speech.isEmpty()) {
                sendTextBestEffort(toUserId, "请告诉我要说的话，例如：语音 你好，欢迎使用AI助手");
                return;
            }
            sendVoiceReply(toUserId, speech);
            return;
        }

        // 默认：让大模型判断——文字回复 / 生成图片 / 语音播报
        DashScopeService.ChatResult result = dashScopeService.chatOrGenerate(text);
        if (result.wantsImage()) {
            sendImageReply(toUserId, result.imagePrompt());
        } else if (result.wantsSpeech()) {
            sendVoiceReply(toUserId, result.speechText());
        } else if (result.wantsWeather()) {
            sendWeatherReply(toUserId, result.weatherLocation());
        } else {
            requireClient();
            client.sendText(toUserId, result.text());
            log.info("已向 {} 发送 LLM 文本回复", toUserId);
        }
    }

    private void sendImageReply(String toUserId, String prompt) throws Exception {
        DashScopeService.ImageResult image = dashScopeService.generateImage(prompt);
        requireClient();
        client.sendImage(toUserId, image.bytes(), image.fileName(), prompt);
        log.info("已向 {} 发送文生图结果（{} 字节）", toUserId, image.bytes().length);
    }

    private void sendVoiceReply(String toUserId, String speech) throws Exception {
        DashScopeService.VoiceResult voice = dashScopeService.synthesizeVoice(speech);
        requireClient();
        client.sendFile(toUserId, voice.bytes(), voice.fileName(), "语音回复");
        log.info("已向 {} 发送语音文件（{} 字节）", toUserId, voice.bytes().length);
    }

    private void sendWeatherReply(String toUserId, String location) throws Exception {
        WeatherService.WeatherInfo info = weatherService.getNowWeather(location);
        requireClient();
        client.sendText(toUserId, info.toString());
        log.info("已向 {} 发送天气信息（{}）", toUserId, location);
    }

    private void sendTextBestEffort(String toUserId, String text) {
        ILinkClient c = client;
        if (c == null) {
            return;
        }
        try {
            c.sendText(toUserId, text);
        } catch (Exception e) {
            log.warn("发送错误提示文本失败: {}", e.getMessage());
        }
    }

    /**
     * 手动拉取一次消息。心跳开启时通常无需手动调用。
     */
    public List<WeixinMessage> poll() throws IOException {
        requireClient();
        return client.getUpdates();
    }

    /**
     * 发送文本消息。
     *
     * <p>注意：目标用户必须曾给 bot 发过消息且已被 getUpdates 拉取到 contextToken，
     * 否则 SDK 无法获取会话上下文，会抛出异常。
     */
    public void sendText(String toUserId, String text) throws IOException {
        requireClient();
        client.sendText(toUserId, text);
        log.info("已向 {} 发送文本消息: {}", toUserId, text);
    }

    public boolean isLoggedIn() {
        return client != null && client.isLoggedIn();
    }

    public WeChatStatus status() {
        ILinkClient c = client;
        if (c == null) {
            return new WeChatStatus(false, "NOT_LOGIN", "NOT_CONNECTED", 0);
        }
        return new WeChatStatus(
                c.isLoggedIn(),
                c.getLoginStatus().getStatus().name(),
                c.getConnectionStatus().name(),
                receivedMessages.size());
    }

    public List<ReceivedMessage> getReceivedMessages() {
        return List.copyOf(receivedMessages);
    }

    private void requireClient() {
        if (client == null) {
            throw new IllegalStateException("尚未登录，请先调用 /wechat/qrcode 获取二维码并扫码登录");
        }
    }

    // ===== 登录态持久化 =====

    /** 把当前登录态（含消息游标与会话上下文）保存到本地文件 */
    private synchronized void saveState() {
        ILinkClient c = client;
        if (c == null || c.getLoginContext() == null) {
            return;
        }
        try {
            ResumeContext rc = c.exportResumeContext();
            if (rc == null) {
                return;
            }
            LoginContext lc = rc.getLoginContext();
            Map<String, WeChatState.ConversationState> convs = new LinkedHashMap<>();
            for (Map.Entry<String, ConversationContext> e : rc.getConversationContextMap().entrySet()) {
                ConversationContext v = e.getValue();
                if (v == null) {
                    continue;
                }
                convs.put(e.getKey(), new WeChatState.ConversationState(
                        v.getLatestContextToken(), v.getTypingTicket(),
                        v.getSourceMessageId(), v.getSourceMessageTime()));
            }
            WeChatState state = new WeChatState(
                    lc.getBotToken(), lc.getUserId(), lc.getBotId(), lc.getBaseUrl(),
                    rc.getUpdatesCursor(), convs);
            stateMapper.writeValue(new File(resumeFile), state);
            log.debug("微信登录态已保存到 {}", resumeFile);
        } catch (Exception e) {
            log.warn("保存微信登录态失败: {}", e.getMessage());
        }
    }

    /** 从本地文件恢复登录态，失败或不存在时返回 null */
    private ResumeContext loadResume() {
        File file = new File(resumeFile);
        if (!file.isFile()) {
            return null;
        }
        try {
            WeChatState state = stateMapper.readValue(file, WeChatState.class);
            if (state.botToken() == null || state.botToken().isBlank()) {
                return null;
            }
            LoginContext lc = new LoginContext(
                    state.botToken(), state.userId(), state.botId(), state.baseUrl());
            ResumeContext.Builder builder = ResumeContext.builder(lc);
            if (state.updatesCursor() != null) {
                builder.updatesCursor(state.updatesCursor());
            }
            Map<String, ConversationContext> convs = new LinkedHashMap<>();
            if (state.conversationContexts() != null) {
                for (Map.Entry<String, WeChatState.ConversationState> e
                        : state.conversationContexts().entrySet()) {
                    WeChatState.ConversationState cs = e.getValue();
                    if (cs == null || cs.contextToken() == null || cs.contextToken().isBlank()) {
                        continue;
                    }
                    ConversationContext ctx = new ConversationContext(
                            new ContextKey(state.botId(), e.getKey()));
                    ctx.updateContextToken(cs.contextToken(), cs.sourceMessageId(), cs.sourceMessageTime());
                    if (cs.typingTicket() != null) {
                        ctx.setTypingTicket(cs.typingTicket());
                    }
                    convs.put(e.getKey(), ctx);
                }
            }
            builder.conversationContexts(convs);
            return builder.build();
        } catch (Exception e) {
            log.warn("读取本地登录态失败，将重新登录: {}", e.getMessage());
            return null;
        }
    }

    private void deleteResume() {
        File file = new File(resumeFile);
        if (file.isFile() && file.delete()) {
            log.info("已删除失效的本地登录态文件 {}", resumeFile);
        }
    }

    // ===== 启动自动登录 =====

    @Override
    public void run(ApplicationArguments args) {
        if (!autoLogin) {
            return;
        }
        if (client == null) {
            client = createClient();
        }
        if (client.isLoggedIn()) {
            try {
                // 用一次 getUpdates 验证本地登录态是否仍然有效
                client.getUpdates();
                log.info("已从本地恢复微信登录状态（botId={}），无需重新扫码", client.getLoginContext().getBotId());
                return;
            } catch (Exception e) {
                log.warn("本地登录态已失效（{}），将重新登录", e.getMessage());
                deleteResume();
                client.close();
                client = null;
            }
        }
        try {
            String qrCode = login();
            log.info("========================================");
            log.info("请打开以下链接，扫码登录微信机器人：");
            log.info("{}", qrCode);
            log.info("========================================");
        } catch (Exception e) {
            log.error("启动自动登录失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void close() {
        saveState();
        imageTextMerger.shutdown();
        replyExecutor.shutdownNow();
        ILinkClient c = client;
        if (c != null) {
            c.close();
            log.info("微信客户端已关闭");
        }
    }
}