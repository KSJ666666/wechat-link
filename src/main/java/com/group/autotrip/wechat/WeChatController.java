package com.group.autotrip.wechat;

import com.group.autotrip.agent.DashScopeService;
import com.group.autotrip.agent.MessageRouter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 微信收发消息 + LLM 自测 REST 接口。
 */
@RestController
@RequestMapping("/wechat")
public class WeChatController {

    private final WeChatService weChatService;
    private final DashScopeService dashScopeService;
    private final MessageRouter messageRouter;

    public WeChatController(WeChatService weChatService, DashScopeService dashScopeService, MessageRouter messageRouter) {
        this.weChatService = weChatService;
        this.dashScopeService = dashScopeService;
        this.messageRouter = messageRouter;
    }

    /** 获取登录二维码并开始登录轮询 */
    @GetMapping("/qrcode")
    public Map<String, String> qrcode() {
        return Map.of("qrcode", weChatService.login());
    }

    /** 登录 / 连接状态 */
    @GetMapping("/status")
    public WeChatStatus status() {
        return weChatService.status();
    }

    /** 手动拉取一次消息 */
    @PostMapping("/poll")
    public ResponseEntity<Map<String, Object>> poll() {
        try {
            weChatService.poll();
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /** 发送文本消息 */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendTextRequest request) {
        if (request.toUserId() == null || request.toUserId().isBlank()
                || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "toUserId 和 text 不能为空"));
        }
        try {
            weChatService.sendText(request.toUserId(), request.text());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /** 最近收到的消息（内存中最多保留 100 条） */
    @GetMapping("/messages")
    public List<ReceivedMessage> messages() {
        return weChatService.getReceivedMessages();
    }

    // ===== 以下为 LLM 自测接口，无需微信登录即可验证百炼调用 =====

    /** LLM 文本对话自测 */
    @PostMapping("/llm/chat")
    public ResponseEntity<Map<String, Object>> llmChat(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "text 不能为空"));
        }
        try {
            return ResponseEntity.ok(Map.of("success", true, "reply", dashScopeService.chat(text)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /** 三层消息路由自测：返回命中层级(SKILL/RAG/LLM) + 最终回复 */
    @PostMapping("/route")
    public ResponseEntity<Map<String, Object>> route(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "text 不能为空"));
        }
        try {
            MessageRouter.RouteText r = messageRouter.routeForText(text);
            return ResponseEntity.ok(Map.of("success", true, "tier", r.tier(), "reply", r.reply()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /** 图片理解自测：上传图片文件，返回识别结果 */
    @PostMapping(value = "/llm/vision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> llmVision(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "prompt", defaultValue = "请详细描述这张图片的内容") String prompt) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "file 不能为空"));
        }
        try {
            String description = dashScopeService.describeImage(
                    file.getBytes(), file.getOriginalFilename(), prompt);
            return ResponseEntity.ok(Map.of("success", true, "description", description));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", String.valueOf(e.getMessage())));
        }
    }

    /** 语音合成自测，返回 mp3 音频文件 */
    @PostMapping("/llm/voice")
    public ResponseEntity<byte[]> llmVoice(@RequestBody Map<String, String> body) throws Exception {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body("text 不能为空".getBytes());
        }
        DashScopeService.VoiceResult voice = dashScopeService.synthesizeVoice(text);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header("Content-Disposition", "attachment; filename=" + voice.fileName())
                .body(voice.bytes());
    }

    /** 文生图自测，直接返回图片字节 */
    @PostMapping("/llm/image")
    public ResponseEntity<byte[]> llmImage(@RequestBody Map<String, String> body) throws Exception {
        String prompt = body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body("prompt 不能为空".getBytes());
        }
        DashScopeService.ImageResult image = dashScopeService.generateImage(prompt);
        String contentType = image.fileName().endsWith(".jpg") || image.fileName().endsWith(".jpeg")
                ? "image/jpeg" : "image/png";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(image.bytes());
    }

}