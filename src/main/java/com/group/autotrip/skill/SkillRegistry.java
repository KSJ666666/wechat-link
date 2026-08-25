package com.group.autotrip.skill;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Skill 注册表：Spring 自动收集所有 @Component Skill，按名称建立索引。 */
@Component
public class SkillRegistry {

    private final Map<String, Skill> skills;

    public SkillRegistry(List<Skill> skillList) {
        Map<String, Skill> map = new LinkedHashMap<>();
        for (Skill skill : skillList) {
            Skill prev = map.putIfAbsent(skill.name(), skill);
            if (prev != null) {
                throw new IllegalStateException("技能名冲突：" + skill.name());
            }
        }
        this.skills = Collections.unmodifiableMap(map);
    }

    /** 全部已注册技能 */
    public Collection<Skill> all() {
        return skills.values();
    }
}
