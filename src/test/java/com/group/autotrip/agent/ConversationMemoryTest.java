package com.group.autotrip.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryTest {

    @Test
    void recordsUserAndAssistantInOrder() {
        ConversationMemory memory = new ConversationMemory(6);
        memory.record("u1", "你好", "你好呀");
        List<String> recent = memory.recent("u1");
        assertEquals(List.of("用户：你好", "助手：你好呀"), recent);
    }

    @Test
    void isolatedPerUser() {
        ConversationMemory memory = new ConversationMemory(6);
        memory.record("u1", "问题A", "回答A");
        memory.record("u2", "问题B", "回答B");
        assertEquals(2, memory.recent("u1").size());
        assertEquals(2, memory.recent("u2").size());
        assertTrue(memory.recent("u1").get(0).contains("问题A"));
        assertTrue(memory.recent("u2").get(0).contains("问题B"));
    }

    @Test
    void capsAtMaxSizeDroppingOldest() {
        ConversationMemory memory = new ConversationMemory(4);
        memory.record("u1", "第一轮", "回答1");
        memory.record("u1", "第二轮", "回答2");
        memory.record("u1", "第三轮", "回答3");
        List<String> recent = memory.recent("u1");
        assertEquals(4, recent.size());
        assertTrue(recent.get(0).contains("第二轮"), "最旧一轮应被丢弃");
        assertTrue(recent.get(2).contains("第三轮"));
        assertTrue(recent.get(3).contains("回答3"));
    }

    @Test
    void clearRemovesUserMemory() {
        ConversationMemory memory = new ConversationMemory(6);
        memory.record("u1", "你好", "你好呀");
        memory.clear("u1");
        assertTrue(memory.recent("u1").isEmpty());
    }

    @Test
    void nullUserIdSharesBucket() {
        ConversationMemory memory = new ConversationMemory(6);
        memory.record(null, "你好", "你好呀");
        assertEquals(2, memory.recent(null).size());
        assertEquals(2, memory.recent("").size());
    }
}
