package com.group.autotrip.skill;

import com.group.autotrip.agent.DashScopeService;
import com.group.autotrip.tools.CustomTools;

/** Skill 执行上下文：提供发送者用户 ID、用户文本、工具注册表和 LLM 服务。 */
public record SkillContext(
        String userId,
        String userText,
        CustomTools tools,
        DashScopeService llm) {
}
