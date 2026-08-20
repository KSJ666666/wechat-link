package com.zmy.demo.wechat;

import java.util.Map;

/**
 * 微信登录态的本地持久化结构，用于服务重启后自动恢复登录，无需重复扫码。
 */
public record WeChatState(
        String botToken,
        String userId,
        String botId,
        String baseUrl,
        String updatesCursor,
        Map<String, ConversationState> conversationContexts) {

    /** 单个用户的会话上下文（缓存 contextToken） */
    public record ConversationState(
            String contextToken,
            String typingTicket,
            Long sourceMessageId,
            Long sourceMessageTime) {
    }
}