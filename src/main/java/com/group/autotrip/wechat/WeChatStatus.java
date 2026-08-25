package com.group.autotrip.wechat;

/**
 * 微信登录 / 连接状态。
 */
public record WeChatStatus(
        boolean loggedIn,
        String loginStatus,
        String connectionStatus,
        int receivedCount) {
}