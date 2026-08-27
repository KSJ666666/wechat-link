# 12-group-project

第 12 组微信机器人项目：微信自动收发消息 + 阿里云百炼（DashScope）大模型对话 + 联网搜索 + Function Calling 工具调用 + Skill 技能框架。

本文档覆盖当前代码库的实际功能、启动方式、使用注意事项，以及新增工具、新增 Skill 和团队协作规范。

## 目录

- [快速开始](#快速开始)
- [微信登录与自测接口](#微信登录与自测接口)
- [可用功能](#可用功能)
- [消息处理流程](#消息处理流程)
- [使用注意事项](#使用注意事项)
- [配置说明](#配置说明)
- [新增工具](#新增工具)
- [新增 Skill](#新增-skill)
- [测试](#测试)
- [项目结构](#项目结构)
- [合作开发注意事项](#合作开发注意事项)

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+（也可以直接使用项目自带的 Maven Wrapper）
- 可访问外网（调用阿里云百炼、心知天气和微信 SDK 服务）

### 配置环境变量

启动前需要配置两个 API Key：

```powershell
setx DASHSCOPE_API_KEY "你的阿里云百炼密钥"
setx WEATHER_API_KEY "你的心知天气密钥"
```

`setx` 设置的变量需要新开一个终端才会生效。只想在当前终端临时使用，可以改为：

```powershell
$env:DASHSCOPE_API_KEY = "你的阿里云百炼密钥"
$env:WEATHER_API_KEY = "你的心知天气密钥"
```

`DASHSCOPE_API_KEY` 在[阿里云百炼控制台](https://bailian.console.aliyun.com/)创建；`WEATHER_API_KEY` 在[心知天气控制台](https://www.seniverse.com/)创建。

> 密钥只放到环境变量或本地配置文件里，不要提交到 Git，也不要发到群里。

### 启动项目

```powershell
mvn spring-boot:run
```

也可以使用项目自带的 Maven Wrapper：

```powershell
.\mvnw.cmd spring-boot:run
```

项目默认监听 `http://localhost:8080`。

## 微信登录与自测接口

启动后按以下顺序验证：

1. 打开 `GET http://localhost:8080/wechat/qrcode`，返回微信登录二维码链接。
2. 用测试微信号扫码登录。若此前登录态仍有效，启动时不会再次扫码。
3. 扫码成功后，微信机器人开始自动接收消息并回复。

常用 REST 接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/wechat/qrcode` | 获取微信登录二维码 |
| `GET` | `/wechat/status` | 查看登录 / 连接状态 |
| `POST` | `/wechat/poll` | 手动拉取一次消息 |
| `POST` | `/wechat/send` | 主动发送文本，请求体 `{"toUserId":"...", "text":"..."}` |
| `GET` | `/wechat/messages` | 查看最近收到的消息（内存中最多 100 条） |
| `POST` | `/wechat/llm/chat` | 不登录微信也能测试 LLM，请求体 `{"text":"郑州天气怎么样"}` |

## 可用功能

| 功能 | 说明 |
| --- | --- |
| 微信自动回复 | 接收文本消息，以及服务端已转写文字的语音消息，自动调用 LLM 回复 |
| LLM 对话 | 使用 `qwen-plus` 模型进行文本对话 |
| 联网搜索 | 工具无法回答时自动联网搜索兜底，命中实时类关键词时优先搜索 |
| 天气查询 | 通过 `query_weather` 工具调用心知天气接口，返回具体城市的实时天气 |
| Function Calling | 模型可自动调用已注册工具，支持一轮多工具并行或串行执行 |
| Skill 技能 | 关键词命中的技能直接执行，不依赖模型自行判断；当前已具备框架，可继续添加技能 |
| 旅行规划 Skill | 命中明确的自驾旅行需求时，会串联路线、景点、距离和天气工具生成路书 |
| 开发自测接口 | `/wechat/llm/chat` 等接口可在不登录微信时验证 LLM 和工具链路 |

## 消息处理流程

```text
微信消息（文本 / 已转写文字的语音）
  └─ chatOrGenerate()
     ├─ 命中 Skill → 执行技能并直接返回
     ├─ 模型请求调用工具 → 执行工具 → 多轮循环直到模型给出最终回复
     └─ 模型未调用工具 → 联网搜索兜底 → 返回最终回复
```

## 使用注意事项

- 未配置 `DASHSCOPE_API_KEY` 时，LLM 调用会报“未配置阿里云百炼 API Key”；未配置 `WEATHER_API_KEY` 时，天气工具会执行失败。
- 天气查询只支持具体城市（如“郑州”“上海”或拼音 `zhengzhou`），不支持省份、自治区等省级区域；用户问“河南天气”时，机器人会提示提供具体城市名。
- 当前代码未内置多轮对话记忆，每次微信消息都是独立调用 LLM；重启后内存中的消息记录也会清空。
- 微信语音消息依赖服务端把语音转成文字；如果 SDK 未返回转写文字，则不会回复。
- `/wechat/send` 只能给“曾经给 bot 发过消息且已被 SDK 拉取过会话上下文”的用户发送，否则会缺少 contextToken。
- 微信登录态保存在 `~/.wechat-demo-resume.json`，包含会话凭据，不要提交或分享；登录态失效时会自动删除并重新扫码。
- 消息回复是单线程顺序处理的，LLM 较慢时新消息会排队等待，不会并发回复同一用户。
- 工具默认并行执行（`dashscope.tool-execution-mode=parallel`），多工具同时调用外部接口时要注意第三方 API 限流；需要严格串行时可改为 `serial`。
- 一次消息最多执行 5 轮工具调用，超过后会报错退出。
- 联网搜索和模型调用会产生 API 费用，长时间运行或高频测试前先确认额度。
- REST 接口没有鉴权，只适合本机或内网开发调试，不要直接暴露到公网。
- Windows 控制台中文乱码时，可用 Windows Terminal，或在启动前执行 `chcp 65001`。

## 配置说明

配置集中在 `src/main/resources/application.properties`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `dashscope.api-key` | `${DASHSCOPE_API_KEY:}` | 阿里云百炼 API Key |
| `weather.api-key` | `${WEATHER_API_KEY:}` | 心知天气 API Key |
| `amap.api-key` | `${AMAP_API_KEY:}` | 高德地图 Web Service API Key |
| `dashscope.chat-model` | `qwen-plus` | 对话模型 |
| `dashscope.tool-execution-mode` | `parallel` | 工具执行模式：`serial` 或 `parallel` |
| `dashscope.tool-execution-threads` | `4` | 并行模式下工具执行线程数 |
| `dashscope.enable-search` | `true` | 是否开启联网搜索兜底 |
| `dashscope.forced-search` | `true` | 命中实时类关键词后是否强制搜索 |
| `dashscope.search-extension` | `true` | 是否启用垂域搜索 |
| `wechat.auto-login` | `true` | 启动时自动恢复登录态或打印二维码 |
| `wechat.resume-file` | `${user.home}/.wechat-demo-resume.json` | 微信登录态保存位置 |
| `logging.charset.console` | `UTF-8` | 控制台日志编码 |

## 新增工具

新增工具只需要实现 `com.group.autotrip.common.FunctionTool`，不需要修改 `CustomTools`、`DashScopeService` 等公共代码。

步骤：

1. 在 `src/main/java/com/group/autotrip/tools/` 下新建一个类。
2. 让类实现 `FunctionTool`，并加 `@Component` 注解。
3. 实现 4 个方法：`name()`、`description()`、`parameters()`、`execute()`。
4. 工具名必须在所有工具中全局唯一，否则 Spring 启动时会报“工具名冲突”。
5. 重启项目后工具会自动注册到模型工具列表和系统提示词中。

示例：

```java
package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

@Component
public class ExampleTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "example_tool";
    }

    @Override
    public String description() {
        return "在用户询问某个示例内容时调用，例如“...”。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        params.putObject("properties")
                .putObject("keyword")
                .put("type", "string")
                .put("description", "示例参数");
        params.putArray("required").add("keyword");
        params.put("additionalProperties", false);
        return params;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String keyword = args.path("keyword").asText("");
        return "查询结果：" + keyword;
    }
}
```

`execute()` 返回给模型的必须是文本字符串；工具内部可以调用第三方接口，但不要返回 Java 对象。

## 新增 Skill

新增 Skill 只需要实现 `com.group.autotrip.skill.Skill`，不需要修改 `SkillRegistry` 或 `SkillDispatcher`。

步骤：

1. 在 `src/main/java/com/group/autotrip/skill/` 下新建一个类。
2. 让类实现 `Skill`，并加 `@Component` 注解。
3. 实现 4 个方法：`name()`、`description()`、`supports()`、`execute()`。
4. `name()` 在所有 Skill 中全局唯一。
5. `supports()` 用关键词判断是否由本 Skill 处理；`SkillDispatcher` 按注册顺序匹配，命中第一个就执行，因此关键词不要写得过于宽泛。
6. 在 `execute()` 中可以通过 `ctx.userText()` 取用户消息，通过 `ctx.tools()` 调用工具，通过 `ctx.llm()` 调用 LLM。

当前仓库中的旅行规划 Skill 会先解析出发地、目的地、天数和偏好，再串联 `query_weather`、`query_weather_forecast`、`query_route`、`query_attractions`、`query_attraction_detail`、`query_distance_matrix`、`search_poi` 和 `query_traffic`，最后交给 `ctx.llm()` 组织成适合直接发送的路书。

示例：

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

Skill 会在工具调用和 LLM 兜底之前执行，适合做固定流程、固定回复或需要确定性结果的场景。

## 测试

```powershell
mvn test
```

当前测试覆盖 Spring 上下文加载和工具串行 / 并行执行，不需要真实 API Key。

使用 Maven Wrapper 时执行：

```powershell
.\mvnw.cmd test
```

## 项目结构

```text
src/main/java/com/group/autotrip/
├── DemoApplication.java         Spring Boot 启动类
├── agent/
│   └── DashScopeService.java    LLM 对话、联网搜索、工具调用与多轮闭环
├── common/
│   └── FunctionTool.java        工具接口
├── skill/
│   ├── Skill.java               Skill 接口
│   ├── SkillContext.java        Skill 执行上下文
│   ├── SkillRegistry.java       Skill 注册表
│   └── SkillDispatcher.java     Skill 关键词调度器
├── tools/
│   ├── CustomTools.java         工具注册与分发
│   ├── QueryWeatherTool.java    天气查询工具
│   └── WeatherService.java      心知天气 HTTP 服务
└── wechat/
    ├── WeChatController.java    微信 REST 接口
    ├── WeChatService.java       微信登录、收发消息、自动回复
    └── ...                      消息与登录态相关数据结构
```

## 合作开发注意事项

- 每人一个分支，分支名 = 姓名拼音。
- 只推自己的分支，禁止直接推 `master`。
- 合并到 `master` 由组长统一操作，不要自行合并或覆盖其他成员分支。
- 不要用 `--force` 推送，避免覆盖他人提交。
- 提交前先检查 `git status`，只提交自己本次改动的文件，不要顺手带上无关改动。
- 不要提交 `target/`、`.idea/`、`.codegraph/`、微信登录态文件或任何 API Key。
- 新增工具或 Skill 时优先只增加新类，不要改动 `CustomTools`、`SkillRegistry`、`SkillDispatcher` 等公共注册代码，减少合并冲突。
- 尽量按模块拆分工作，避免多人同时修改同一个文件；必须共改时先和负责该模块的成员沟通。
- 推送前运行 `.\mvnw.cmd test`，确保测试通过。
- 新增功能后同步更新本文档的功能列表和配置说明。

