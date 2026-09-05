# wechat-link

第 12 组微信机器人项目：微信自动收发消息 + 阿里云百炼（DashScope）大模型对话 + 联网搜索 + Function Calling 工具调用 + Skill 技能框架，目前已完成天气和高德出行类工具。

本文档覆盖当前代码库的实际功能、启动方式、使用注意事项，以及新增工具、新增 Skill 和团队协作规范。

## 目录

- [当前进度](#当前进度)
- [快速开始](#快速开始)
- [微信登录与自测接口](#微信登录与自测接口)
- [可用功能](#可用功能)
- [消息处理流程](#消息处理流程)
- [使用注意事项](#使用注意事项)
- [配置说明](#配置说明)
- [工具层](#工具层)
- [新增工具](#新增工具)
- [新增 Skill](#新增-skill)
- [Skill 调用工具](#skill-调用工具)
- [测试](#测试)
- [项目结构](#项目结构)
- [合作开发注意事项](#合作开发注意事项)

## 当前进度

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 微信接入 | 已完成 | 二维码登录、登录态持久化、文本 / 已转写语音自动回复 |
| LLM 对话与联网搜索 | 已完成 | 阿里云百炼 `qwen-plus`，工具未命中时联网搜索兜底 |
| Skill 技能框架 | 已完成 | 关键词调度框架可运行，已内置“旅行规划”“行程护航”技能 |
| 天气工具 | 已完成 | 实时天气 `query_weather` + 未来天气预报 `query_weather_forecast` |
| 高德地图工具 | 已完成 | POI 搜索、景点、路况、路线、距离矩阵 |
| 出行推荐 | 已完成 | 综合距离、城市地铁、高峰时段和用户偏好推荐交通方式 |
| 景点指南 RAG 检索 | 已完成 | 指南 JSON 清洗 → 切分 → 阿里云嵌入向量化 → Milvus + VSM 混合检索 → 重排 → LLM 生成 |
| 行程护航（定时监控） | 已完成 | 注册监控 → 定时巡检 → LLM 判断规则 → 触发时微信主动推送告警 |
| 行程数据模型 | 已完成 | `Route` / `Itinerary` / `DayPlan` / `Spot` 等模型已定义，旅行规划 Skill 产出结构化行程单（含状态与预算明细） |
| 测试 | 已完成 | 当前 69 个测试用例全部通过，覆盖工具执行、路况、交通推荐、RAG 检索、行程护航与行程规划 |

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+（也可以直接使用项目自带的 Maven Wrapper）
- 可访问外网（调用阿里云百炼、心知天气、高德地图和微信 SDK 服务）

### 配置环境变量

启动前需要配置三个 API Key：

```powershell
setx DASHSCOPE_API_KEY "你的阿里云百炼密钥"
setx WEATHER_API_KEY "你的心知天气密钥"
setx AMAP_API_KEY "你的高德 Web 服务密钥"
```

`setx` 设置的变量需要新开一个终端才会生效。只想在当前终端临时使用，可以改为：

```powershell
$env:DASHSCOPE_API_KEY = "你的阿里云百炼密钥"
$env:WEATHER_API_KEY = "你的心知天气密钥"
$env:AMAP_API_KEY = "你的高德 Web 服务密钥"
```

`DASHSCOPE_API_KEY` 在[阿里云百炼控制台](https://bailian.console.aliyun.com/)创建；`WEATHER_API_KEY` 在[心知天气控制台](https://www.seniverse.com/)创建；`AMAP_API_KEY` 在[高德开放平台](https://console.amap.com/)创建。

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
| 实时天气 | `query_weather`：查询具体城市的当前天气 |
| 天气预报 | `query_weather_forecast`：查询未来 1-15 天预报，默认 3 天 |
| 地点搜索 | `search_poi`：搜索餐厅、酒店、景点、商场等地点 |
| 景点查询 | `query_attractions` 查询景点列表，`query_attraction_detail` 查询评分、开放时间等详情 |
| 实时路况 | `query_traffic`：查询某城市某条道路的拥堵情况（基于免费驾车路线接口的通行速度估算） |
| 出行路线 | `query_route`：查询两地点间的步行、公交、地铁、驾车、高铁方案并给出推荐 |
| 距离比较 | `query_distance_matrix`：一次计算一个起点到多个目的地的距离和耗时 |
| 景点指南检索 | `query_guide_rag`：基于本地知识库（大理/杭州/上海/长沙景点指南）的 RAG 问答，向量 + 关键词混合检索、重排后由大模型生成带来源的答案 |
| Function Calling | 模型可自动调用已注册工具，支持一轮多工具并行或串行执行 |
| Skill 技能 | 关键词命中的技能直接执行，不依赖模型自行判断；已内置“旅行规划”“行程护航”技能，可继续添加 |
| 旅行规划 Skill | 命中明确的自驾旅行需求时，串联路线、景点、距离和天气工具，生成结构化行程单（含状态、预算明细）；支持“重新排”重规划 |
| 行程护航 | 发送“监控郑州天气，低于0度就提醒我”开启监控（支持天气/路况/时间/预算）；机器人定时巡检，规则触发时主动推送告警；“查看监控”“取消监控”管理 |
| 多轮对话记忆 | 按用户保留最近 10 条消息作为 LLM 上下文（仅内存，重启清空） |
| 本地状态持久化 | 监控列表与行程单保存到 `~/.autotrip-state.json`，重启后自动恢复 |
| 开发自测接口 | `/wechat/llm/chat` 等接口可在不登录微信时验证 LLM 和工具链路 |

## 消息处理流程

```text
微信消息（文本 / 已转写文字的语音）
  └─ chatOrGenerate()
     ├─ ① 命中 Skill 关键词 → 执行技能并直接返回
     ├─ ② 命中 RAG 关键词（大理/杭州/上海/长沙 + 景点类意图）→ 增强 Prompt → LLM 回复
     └─ ③ 都没命中 → LLM 多轮工具调用（未调用工具时联网搜索兜底）→ 返回最终回复
```

## 使用注意事项

- 未配置 `DASHSCOPE_API_KEY` 时，LLM 调用会报“未配置阿里云百炼 API Key”；未配置 `WEATHER_API_KEY` 时，天气工具会执行失败；未配置 `AMAP_API_KEY` 时，POI、景点、路况、路线、距离矩阵工具会执行失败。
- 天气工具只支持具体城市（如“郑州”“上海”或拼音 `zhengzhou`），不支持省份、自治区等省级区域；用户问“河南天气”时，机器人会提示提供具体城市名。地点搜索建议携带城市，未提供城市时可能在全国范围搜索。
- 多轮对话记忆按用户保留最近 10 条消息作为 LLM 上下文，仅保存在内存中，重启后清空。
- 微信语音消息依赖服务端把语音转成文字；如果 SDK 未返回转写文字，则不会回复。
- `/wechat/send` 只能给“曾经给 bot 发过消息且已被 SDK 拉取过会话上下文”的用户发送，否则会缺少 contextToken。
- 微信登录态保存在 `~/.wechat-demo-resume.json`，包含会话凭据，不要提交或分享；登录态失效时会自动删除并重新扫码。
- 消息回复是单线程顺序处理的，LLM 较慢时新消息会排队等待，不会并发回复同一用户。
- 工具默认并行执行（`dashscope.tool-execution-mode=parallel`），多工具同时调用外部接口时要注意第三方 API 限流；需要严格串行时可改为 `serial`。
- 一次消息最多执行 12 轮工具调用（`dashscope.tool-execution-max-rounds` 可调），达到上限后会自动让模型基于已有结果收尾。
- 高德 Web 服务有并发和每日配额限制，代码内已限制最多 2 个并发请求并在限流时重试一次，但高频使用仍可能触发配额耗尽。
- 交通推荐依赖 `cities-transport.json` 中的城市地铁 / 铁路档案；未收录的城市仍可查询，但无法判断是否通地铁。
- 实时路况为估算值：高德官方"交通态势"是商务合作高级接口，本项目改用免费的地理编码 + 驾车路线接口，按走廊通行速度推算拥堵等级，并非官方拥堵指数。
- 景点指南 RAG 检索依赖本机 Milvus 容器（`localhost:19530`）和 `DASHSCOPE_API_KEY`；Milvus 不可用或嵌入失败时自动降级为纯关键词检索，不影响其他功能。
- 启动时会自动重建 RAG 索引（清洗 → 切分 → 向量化 → 写入 Milvus，40 个景点一般十几秒完成）；知识块持久化在 Milvus，内存 VSM 关键词索引重启后重建。
- 微信端询问大理 / 杭州 / 上海 / 长沙景点时模型会调用 `query_guide_rag`，链路含嵌入、检索、重排和 LLM 生成，回复会比普通消息慢几秒。
- 行程护航监控列表与行程单持久化在 `~/.autotrip-state.json`，重启后自动恢复；告警推送需要微信已登录且目标用户有有效会话上下文，推送失败只记日志。
- 每条监控每次巡检消耗 1 次工具调用 + 1 次 LLM 判断（当前配置 1 分钟一轮，演示后建议调回 30 分钟以节省费用）；规则触发判断依赖 LLM，解析不出结果时按不触发处理（宁可不打扰）。每轮巡检的查询数据与触发/未触发结论都会打印到控制台日志。
- 预算类监控需要先有行程单（发送“帮我规划三天杭州行程”生成）；天气监控只支持具体城市，路况监控注册时请写成“城市 道路”格式。
- 监控列表与行程单持久化在 `~/.autotrip-state.json`（重启自动恢复）；对话记忆仅内存保存，重启清空。
- 联网搜索和模型调用会产生 API 费用，长时间运行或高频测试前先确认额度。
- REST 接口没有鉴权，只适合本机或内网开发调试，不要直接暴露到公网。
- Windows 控制台中文乱码时，可用 Windows Terminal，或在启动前执行 `chcp 65001`。

## 配置说明

配置集中在 `src/main/resources/application.properties`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `dashscope.api-key` | `${DASHSCOPE_API_KEY:}` | 阿里云百炼 API Key |
| `weather.api-key` | `${WEATHER_API_KEY:}` | 心知天气 API Key |
| `amap.api-key` | `${AMAP_API_KEY:}` | 高德开放平台 Web 服务 API Key |
| `dashscope.chat-model` | `qwen-plus` | 对话模型 |
| `dashscope.tool-execution-mode` | `parallel` | 工具执行模式：`serial` 或 `parallel` |
| `dashscope.tool-execution-threads` | `4` | 并行模式下工具执行线程数 |
| `dashscope.tool-execution-max-rounds` | `12` | 单次消息最多工具调用轮数 |
| `dashscope.enable-search` | `true` | 是否开启联网搜索兜底 |
| `dashscope.forced-search` | `true` | 命中实时类关键词后是否强制搜索 |
| `dashscope.search-extension` | `true` | 是否启用垂域搜索 |
| `rag.embedding.model` | `text-embedding-v3` | RAG 嵌入模型（阿里云百炼，API Key 复用 `DASHSCOPE_API_KEY`） |
| `rag.embedding.dimensions` | `1024` | 嵌入向量维度（需与 Milvus 集合一致） |
| `rag.milvus.host` / `rag.milvus.port` | `localhost` / `19530` | Milvus 向量库连接地址 |
| `rag.milvus.collection` | `trip_guide_chunks` | Milvus 集合名 |
| `rag.retrieve.candidates` / `rag.retrieve.top-k` | `20` / `5` | 每路召回候选数 / 重排后进入 Prompt 的知识块数 |
| `rag.rerank.model` | `gte-rerank-v2` | 重排模型，调用失败自动降级本地规则重排 |
| `rag.index.auto-build` | `true` | 启动时自动构建索引 |
| `monitor.enabled` | `true` | 是否开启行程护航定时巡检 |
| `monitor.check-interval-minutes` | `1` | 行程护航巡检间隔（分钟，演示用；长期运行建议调大） |
| `app.state-file` | `${user.home}/.autotrip-state.json` | 监控列表与行程单的持久化文件 |
| `agent.memory-size` | `10` | 每个用户保留的最近对话条数（仅内存） |
| `wechat.auto-login` | `true` | 启动时自动恢复登录态或打印二维码 |
| `wechat.resume-file` | `${user.home}/.wechat-demo-resume.json` | 微信登录态保存位置 |
| `logging.charset.console` | `UTF-8` | 控制台日志编码 |

## 工具层

工具层由以下几个部分组成：

| 组件 | 类 | 作用 |
| --- | --- | --- |
| 工具接口 | `com.group.autotrip.common.FunctionTool` | 定义工具的名称、描述、参数 Schema 和执行方法 |
| 工具注册表 | `com.group.autotrip.tools.CustomTools` | Spring 自动收集所有 `@Component` 的 `FunctionTool`，按工具名分发执行 |
| 工具执行入口 | `com.group.autotrip.agent.DashScopeService` | 把已注册工具列表交给模型，模型请求后调用 `CustomTools.execute()` |
| 外部数据服务 | `WeatherService`、`AmapService` | 封装心知天气和高德 Web API，供具体工具调用 |
| 城市交通档案 | `CityTransportSupport` | 启动时读取 `cities-transport.json`，提供城市 adcode、地铁 / 铁路能力 |
| 出行推荐器 | `TransportRecommender` | 综合距离、是否同城、城市地铁、高峰时段和用户偏好生成推荐 |

当前已注册工具：

| 工具名 | 实现类 | 功能 | 参数 |
| --- | --- | --- | --- |
| `query_weather` | `QueryWeatherTool` | 查询具体城市的实时天气 | `location`：城市中文名、拼音、城市 ID 或经纬度 |
| `query_weather_forecast` | `QueryWeatherForecastTool` | 查询未来 1-15 天天气预报，默认 3 天 | `location`；可选 `days` |
| `search_poi` | `SearchPoiTool` | 按关键词搜索餐厅、酒店、景点、商场等地点 | `keywords`；可选 `city`、`limit` |
| `query_attractions` | `QueryAttractionsTool` | 查询城市景点 / 景区列表 | `city`；可选 `keyword`、`limit` |
| `query_attraction_detail` | `QueryAttractionDetailTool` | 查询单个景点的评分、等级、开放时间、电话、地址 | `city`、`name` |
| `query_traffic` | `QueryTrafficTool` | 查询某条道路的实时拥堵情况 | `city`、`road` |
| `query_route` | `QueryRouteTool` | 查询两地间多种交通方式并给出推荐 | `origin`、`destination`；可选 `city`、`originCity`、`destinationCity`、`mode`、`prefer` |
| `query_distance_matrix` | `QueryDistanceMatrixTool` | 一次计算起点到多个目的地的距离和耗时并排序 | `origin`、`destinations`；可选 `city`、`mode` |
| `query_guide_rag` | `RagKnowledgeTool` | 大理/杭州/上海/长沙景点指南 RAG 问答 | `query`；可选 `city` |

`CustomTools.execute("工具名", JsonNode 参数)` 会按名称找到工具并执行，返回给模型的必须是字符串。工具名重复时 Spring 启动会直接报“工具名冲突”。

工具既能被模型通过 Function Calling 自动调用，也能被 Skill 在 `execute()` 中主动调用。

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

### Skill 调用工具

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

## 测试

```powershell
mvn test
```

当前测试覆盖 Spring 上下文加载、工具串行 / 并行执行、实时路况工具、交通出行推荐，RAG 的指南加载、清洗、切分、VSM 关键词检索、RRF 混合融合与本地重排兜底，行程护航的注册表隔离、触发裁判解析与技能命令路由，以及旅行规划的解析兜底、行程单排版、重规划关键词与状态持久化，共 69 个用例，不需要真实 API Key。

使用 Maven Wrapper 时执行：

```powershell
.\mvnw.cmd test
```

## 项目结构

```text
src/main/java/com/group/autotrip/
├── DemoApplication.java         Spring Boot 启动类
├── agent/
│   ├── DashScopeService.java    LLM 对话、三级路由（Skill → RAG → LLM）、工具调用多轮闭环
│   ├── ConversationMemory.java  多轮对话记忆（内存，按用户）
│   ├── MonitorService.java      行程护航：监控注册表、定时巡检、LLM 触发判断、微信告警推送
│   ├── TripPlanStore.java       按用户保存最新行程单
│   └── StateStore.java          监控列表与行程单的本地 JSON 持久化
├── common/
│   ├── FunctionTool.java        工具接口
│   └── model/                   Route / Itinerary / DayPlan / Spot / RouteOption / TransportMode
├── output/
│   └── ItineraryOutput.java      行程单成品排版（从 Itinerary 渲染）
├── rag/
│   ├── RagService.java          RAG 问答编排（query 向量化 → 混合检索 → 重排 → Prompt → LLM）
│   ├── RagIndexer.java          建库编排（清洗 → 切分 → 向量化 → Milvus / VSM 索引）
│   ├── RagKnowledgeTool.java    景点指南 RAG 工具（query_guide_rag）
│   ├── RagController.java       调试接口 /rag/status、/rag/reindex、/rag/ask
│   ├── ingest/                  GuideDataLoader / GuideCleaner / GuideChunker（知识源加载、清洗、切分）
│   ├── embed/                   DashScopeEmbeddingClient（阿里云嵌入客户端）
│   ├── store/                   MilvusVectorStore / VsmKeywordIndex（向量库 + 内存 VSM 关键词索引）
│   ├── retrieve/                HybridRetriever / Reranker / RagPromptBuilder（混合检索、重排、Prompt）
│   └── model/                   知识块等数据结构
├── skill/
│   ├── Skill.java               Skill 接口
│   ├── SkillContext.java        Skill 执行上下文
│   ├── SkillRegistry.java       Skill 注册表
│   ├── SkillDispatcher.java     Skill 关键词调度器
│   ├── TripGuardSkill.java      行程护航技能（注册/查看/取消监控）
│   └── TripPlanSkill.java       旅行规划技能（结构化行程单 + 重规划）
├── tools/
│   ├── CustomTools.java         工具注册与分发
│   ├── QueryWeatherTool.java    天气查询工具
│   ├── QueryWeatherForecastTool.java  天气预报工具
│   ├── WeatherService.java      心知天气 HTTP 服务
│   ├── AmapService.java         高德 Web API 封装
│   ├── SearchPoiTool.java       地点搜索工具
│   ├── QueryAttractionsTool.java 景点列表工具
│   ├── QueryAttractionDetailTool.java 景点详情工具
│   ├── QueryTrafficTool.java    实时路况工具
│   ├── QueryRouteTool.java      出行路线工具
│   ├── QueryDistanceMatrixTool.java 距离矩阵工具
│   ├── CityTransportSupport.java 城市交通档案
│   └── TransportRecommender.java 出行方式推荐
└── wechat/
    ├── WeChatController.java    微信 REST 接口
    ├── WeChatService.java       微信登录、收发消息、自动回复
    └── ...                      消息与登录态相关数据结构

src/main/resources/
└── cities-transport.json         城市地铁 / 铁路能力与 adcode 档案
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
- 推送前运行 `mvn test`，确保测试通过。
- 新增功能后同步更新本文档的功能列表和配置说明。
