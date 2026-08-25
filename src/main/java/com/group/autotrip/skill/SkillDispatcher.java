package com.group.autotrip.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Skill 调度器：按关键词匹配第一个命中技能并执行，未命中返回空。 */
@Component
public class SkillDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillDispatcher.class);

    private final SkillRegistry skillRegistry;

    public SkillDispatcher(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /** 尝试执行 Skill；命中返回技能结果，未命中返回 empty。 */
    public Optional<String> tryExecute(SkillContext ctx) {
        for (Skill skill : skillRegistry.all()) {
            if (skill.supports(ctx.userText())) {
                log.info("命中技能：{}，用户消息：{}", skill.name(), ctx.userText());
                try {
                    return Optional.of(skill.execute(ctx.userText(), ctx));
                } catch (Exception e) {
                    log.error("技能 {} 执行失败: {}", skill.name(), e.getMessage(), e);
                    return Optional.of("技能执行失败：" + e.getMessage());
                }
            }
        }
        return Optional.empty();
    }
}
