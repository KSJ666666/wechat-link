package com.group.autotrip.skill;

/**
 * Skill 插件接口，由 SkillRegistry 自动收集，SkillDispatcher 按关键词匹配后调用。
 *
 * <p>实现类标注 @Component 后由 Spring 自动收集。
 */
public interface Skill {

    /** 技能名（模型调用时使用的名字，全局唯一） */
    String name();

    /** 技能描述（告诉模型该技能做什么、什么时候调用） */
    String description();

    /** 关键词匹配：判断这条用户消息是否归本技能处理 */
    boolean supports(String userText);

    /** 执行技能，返回给用户的文本结果 */
    String execute(String userText, SkillContext ctx) throws Exception;
}
