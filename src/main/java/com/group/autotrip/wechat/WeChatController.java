package com.group.autotrip.wechat;

import com.group.autotrip.agent.DashScopeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public WeChatController(WeChatService weChatService, DashScopeService dashScopeService) {
        this.weChatService = weChatService;
        this.dashScopeService = dashScopeService;
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

}
