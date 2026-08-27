package com.group.autotrip.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆：按用户保存最近 N 条消息（用户与助手交替），仅内存保存、重启清空。
 */
@Component
public class ConversationMemory {

    private final Map<String, Deque<String>> historyByUser = new ConcurrentHashMap<>();
    private final int maxSize;

    public ConversationMemory(@Value("${agent.memory-size:6}") int maxSize) {
        this.maxSize = Math.max(2, maxSize);
    }

    /** 记录一轮对话（用户消息 + 助手回复），超出上限丢弃最旧 */
    public void record(String userId, String userText, String reply) {
        if (userText == null || userText.isBlank()) {
            return;
        }
        Deque<String> history = historyByUser.computeIfAbsent(
                keyOf(userId), k -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast("用户：" + userText);
            history.addLast("助手：" + reply);
            while (history.size() > maxSize) {
                history.removeFirst();
            }
        }
    }

    /** 最近对话记录（旧 → 新），无记忆时返回空列表 */
    public List<String> recent(String userId) {
        Deque<String> history = historyByUser.get(keyOf(userId));
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /** 清空某用户记忆 */
    public void clear(String userId) {
        historyByUser.remove(keyOf(userId));
    }

    private static String keyOf(String userId) {
        return userId == null ? "" : userId;
    }
}
