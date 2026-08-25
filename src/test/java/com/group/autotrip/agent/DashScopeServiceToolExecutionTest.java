package com.group.autotrip.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import com.group.autotrip.skill.SkillDispatcher;
import com.group.autotrip.skill.SkillRegistry;
import com.group.autotrip.tools.CustomTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashScopeServiceToolExecutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class SlowTool implements FunctionTool {
        private final String name;
        private final AtomicInteger active;
        private final AtomicInteger maxConcurrent;

        SlowTool(String name, AtomicInteger active, AtomicInteger maxConcurrent) {
            this.name = name;
            this.active = active;
            this.maxConcurrent = maxConcurrent;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parameters() {
            return MAPPER.createObjectNode();
        }

        @Override
        public String execute(JsonNode args) throws InterruptedException {
            int now = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            Thread.sleep(150);
            active.decrementAndGet();
            return name;
        }
    }

    private static JsonNode toolCalls() {
        ArrayNode calls = MAPPER.createArrayNode();
        ObjectNode first = calls.addObject();
        first.put("id", "call_1");
        first.putObject("function").put("name", "tool_a").put("arguments", "{}");
        ObjectNode second = calls.addObject();
        second.put("id", "call_2");
        second.putObject("function").put("name", "tool_b").put("arguments", "{}");
        return calls;
    }

    @Test
    void parallelModeExecutesToolsConcurrentlyAndKeepsOrder() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CustomTools customTools = new CustomTools(List.of(
                new SlowTool("tool_a", active, maxConcurrent),
                new SlowTool("tool_b", active, maxConcurrent)));
        DashScopeService service = new DashScopeService(
                customTools, new SkillDispatcher(new SkillRegistry(List.of())), "parallel", 2);
        try {
            List<String> results = service.executeTools(toolCalls());
            assertEquals(List.of("tool_a", "tool_b"), results);
            assertTrue(maxConcurrent.get() >= 2, "并行模式应同时执行多个工具，实际最大并发=" + maxConcurrent.get());
        } finally {
            service.close();
        }
    }

    @Test
    void serialModeExecutesToolsOneByOneAndKeepsOrder() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CustomTools customTools = new CustomTools(List.of(
                new SlowTool("tool_a", active, maxConcurrent),
                new SlowTool("tool_b", active, maxConcurrent)));
        DashScopeService service = new DashScopeService(
                customTools, new SkillDispatcher(new SkillRegistry(List.of())), "serial", 2);
        try {
            List<String> results = service.executeTools(toolCalls());
            assertEquals(List.of("tool_a", "tool_b"), results);
            assertEquals(1, maxConcurrent.get(), "串行模式最大并发应为 1");
        } finally {
            service.close();
        }
    }
}
