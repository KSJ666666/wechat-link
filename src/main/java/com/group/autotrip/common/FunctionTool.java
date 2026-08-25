package com.group.autotrip.common;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Function Calling 自定义工具接口。
 *
 * <p>实现类标注 {@code @Component} 后由 Spring 自动收集（见 {@link CustomTools}），
 * 无需修改任何公共代码即可注册到工具列表、系统提示词并参与执行。
 */
public interface FunctionTool {

    /** 工具名（模型调用时使用的名字，全局唯一） */
    String name();

    /** 工具描述（告诉模型该工具做什么、什么时候调用） */
    String description();

    /** 参数 JSON Schema（OpenAI function parameters） */
    JsonNode parameters();

    /** 执行工具，返回给模型的文本结果 */
    String execute(JsonNode args) throws Exception;
}
