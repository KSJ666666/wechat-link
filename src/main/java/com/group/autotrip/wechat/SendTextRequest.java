package com.group.autotrip.wechat;

/**
 * 发送文本消息请求体。
 */
public record SendTextRequest(String toUserId, String text) {
}