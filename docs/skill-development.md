# 新增 Skill

新增 Skill 只需要实现 `com.group.autotrip.skill.Skill`，不需要修改 `SkillRegistry` 或 `SkillDispatcher`。

Skill 会在工具调用和 LLM 兜底之前执行，适合做固定流程、固定回复或需要确定性结果的场景。

## 当前已内置 Skill

| Skill | 类 | 功能 |
| --- | --- | --- |
| 旅行规划 | `TripPlanSkill` | 解析出发地、目的地、天数和偏好，串联路线、景点、距离和天气工具，生成结构化行程单（含状态、预算明细），支持「重新排」重规划 |
| 行程护航 | `TripGuardSkill` | 注册/查看/取消天气、路况、时间、预算类监控 |

## 新增步骤

1. 在 `src/main/java/com/group/autotrip/skill/` 下新建一个类。
2. 让类实现 `Skill`，并加 `@Component` 注解。
3. 实现 4 个方法：`name()`、`description()`、`supports()`、`execute()`。
4. `name()` 在所有 Skill 中全局唯一。
5. `supports()` 用关键词判断是否由本 Skill 处理；`SkillDispatcher` 按注册顺序匹配，命中第一个就执行，因此关键词不要写得过于宽泛。
6. 在 `execute()` 中可以通过 `ctx.userText()` 取用户消息，通过 `ctx.tools()` 调用工具，通过 `ctx.llm()` 调用 LLM。

## 示例

```java
package com.group.autotrip.skill;

import org.springframework.stereotype.Component;

@Component
public class ExampleSkill implements Skill {

    @Override
    public String name() {
        return "example_skill";
    }

    @Override
    public String description() {
        return "处理包含“你好”的问候消息。";
    }

    @Override
    public boolean supports(String userText) {
        return userText != null && userText.contains("你好");
    }

    @Override
    public String execute(String userText, SkillContext ctx) throws Exception {
        return "你好，我是微信机器人。";
    }
}
```

## Skill 调用工具

在 Skill 的 `execute()` 中，通过 `ctx.tools().execute(工具名, 参数)` 调用已注册工具：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Override
public String execute(String userText, SkillContext ctx) throws Exception {
    ObjectNode args = new ObjectMapper().createObjectNode();
    args.put("location", "郑州");

    try {
        return ctx.tools().execute("query_weather", args);
    } catch (Exception e) {
        return "天气查询失败：" + e.getMessage();
    }
}
```

说明：

- `ctx.tools()` 返回工具注册表 `CustomTools`，可以直接按工具名调用任意已注册工具。
- 参数必须是 `JsonNode`，通常用 `ObjectNode` 构造 JSON 对象。
- 工具返回的是文本字符串，可以直接作为 Skill 的回复内容。
- 工具名不存在时会抛 `未知工具` 异常，建议在 Skill 内捕获并转成用户可读的提示。
