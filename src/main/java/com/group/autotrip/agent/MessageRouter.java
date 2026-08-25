package com.group.autotrip.agent;

import com.group.autotrip.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Set;

/**
 * 三层消息路由：
 * <pre>
 *   用户消息
 *     ├─ 命中 Skill 关键词？ → Skill 执行（Function Calling 工具）→ 回复
 *     ├─ 命中 RAG 关键词？  → 检索文档增强 Prompt → LLM 回复
 *     └─ 都没命中？        → 直接 LLM 闲聊回复
 * </pre>
 *
 * <p>Skill 层复用 {@link DashScopeService#chatOrGenerate}（含 calculate/get_time/get_stock
 * 等工具，以及画图/语音/天气透传）；RAG 层用 {@link RagService#buildPrompt} 注入文档；
 * LLM 层用 {@link DashScopeService#chat} 兜底闲聊。
 */
@Service
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final DashScopeService dashScope;
    private final RagService rag;

    /** Skill 关键词：命中则走工具执行路径 */
    private static final Set<String> SKILL_KEYWORDS = Set.of(
            "计算", "算", "等于", "加", "减", "乘", "除",
            "几点", "时间", "现在",
            "股价", "股票", "行情",
            "天气",
            "画", "图片",
            "说", "语音", "播报"
    );

    /** RAG 关键词：命中则检索文档增强 Prompt */
    private static final Set<String> RAG_KEYWORDS = Set.of(
            "帮助", "使用", "说明", "文档", "规则", "关于",
            "你能", "怎么用", "功能", "介绍", "会什么", "能做"
    );

    public MessageRouter(DashScopeService dashScope, RagService rag) {
        this.dashScope = dashScope;
        this.rag = rag;
    }

    /** 完整路由结果：携带 {@link DashScopeService.ChatResult} 与命中层级 */
    public record RouteResult(DashScopeService.ChatResult result, String tier) {
    }

    /** 纯文本路由结果（浏览器自测用）：层级 + 最终文本 */
    public record RouteText(String tier, String reply) {
    }

    /**
     * 完整三层路由，供微信消息链路使用。
     *
     * @param userId 微信用户 id（当前仅用于日志）
     * @param text   用户消息
     */
    public RouteResult route(String userId, String text) throws IOException {
        if (isSkill(text)) {
            log.info("[路由] 命中 Skill 关键词，执行 Function Calling 工具 | user={}", userId);
            return new RouteResult(dashScope.chatOrGenerate(text), "SKILL");
        }
        if (isRag(text)) {
            log.info("[路由] 命中 RAG 关键词，检索文档增强 Prompt | user={}", userId);
            String prompt = rag.buildPrompt(text);
            return new RouteResult(
                    new DashScopeService.ChatResult(dashScope.chat(prompt), null, null, null), "RAG");
        }
        log.info("[路由] 未命中，走 LLM 闲聊兜底 | user={}", userId);
        return new RouteResult(
                new DashScopeService.ChatResult(dashScope.chat(text), null, null, null), "LLM");
    }

    /**
     * 纯文本路由（浏览器自测）：始终返回文本回复，便于页面展示。
     * Skill 层若触发画图/语音/天气，回复前缀标注实际命中的工具。
     */
    public RouteText routeForText(String text) throws IOException {
        if (isSkill(text)) {
            DashScopeService.ChatResult r = dashScope.chatOrGenerate(text);
            String reply = r.wantsImage() ? "[Skill→画图] " + r.imagePrompt()
                    : r.wantsSpeech() ? "[Skill→语音] " + r.speechText()
                    : r.wantsWeather() ? "[Skill→天气] " + r.weatherLocation()
                    : r.text();
            return new RouteText("SKILL", reply);
        }
        if (isRag(text)) {
            String prompt = rag.buildPrompt(text);
            return new RouteText("RAG", dashScope.chat(prompt));
        }
        return new RouteText("LLM", dashScope.chat(text));
    }

    private boolean isSkill(String text) {
        return containsAny(text, SKILL_KEYWORDS);
    }

    private boolean isRag(String text) {
        return containsAny(text, RAG_KEYWORDS);
    }

    private boolean containsAny(String text, Set<String> keys) {
        if (text == null) {
            return false;
        }
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
