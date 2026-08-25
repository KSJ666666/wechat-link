package com.group.autotrip.wechat;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

/**
 * 收到的微信消息（文本场景的简化视图）。
 */
public record ReceivedMessage(
        Long messageId,
        Integer messageType,
        String fromUserId,
        String toUserId,
        Long createTimeMs,
        String contextToken,
        String text) {

    static ReceivedMessage from(WeixinMessage m) {
        return new ReceivedMessage(
                m.getMessage_id(),
                m.getMessage_type(),
                m.getFrom_user_id(),
                m.getTo_user_id(),
                m.getCreate_time_ms(),
                m.getContext_token(),
                extractText(m));
    }

    private static String extractText(WeixinMessage m) {
        if (m.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : m.getItem_list()) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }
}