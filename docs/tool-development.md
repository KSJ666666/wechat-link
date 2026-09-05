# 新增工具

新增工具只需要实现 `com.group.autotrip.common.FunctionTool`，不需要修改 `CustomTools`、`DashScopeService` 等公共代码。

## 工具层组成

| 组件 | 类 | 作用 |
| --- | --- | --- |
| 工具接口 | `com.group.autotrip.common.FunctionTool` | 定义工具的名称、描述、参数 Schema 和执行方法 |
| 工具注册表 | `com.group.autotrip.tools.CustomTools` | Spring 自动收集所有 `@Component` 的 `FunctionTool`，按工具名分发执行 |
| 工具执行入口 | `com.group.autotrip.agent.DashScopeService` | 把已注册工具列表交给模型，模型请求后调用 `CustomTools.execute()` |
| 外部数据服务 | `WeatherService`、`AmapService` | 封装心知天气和高德 Web API，供具体工具调用 |
| 城市交通档案 | `CityTransportSupport` | 启动时读取 `cities-transport.json`，提供城市 adcode、地铁 / 铁路能力 |
| 出行推荐器 | `TransportRecommender` | 综合距离、是否同城、城市地铁、高峰时段和用户偏好生成推荐 |

## 当前已注册工具

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

`CustomTools.execute("工具名", JsonNode 参数)` 会按名称找到工具并执行，返回给模型的必须是字符串。工具名重复时 Spring 启动会直接报「工具名冲突」。

工具既能被模型通过 Function Calling 自动调用，也能被 Skill 在 `execute()` 中主动调用。

## 新增步骤

1. 在 `src/main/java/com/group/autotrip/tools/` 下新建一个类。
2. 让类实现 `FunctionTool`，并加 `@Component` 注解。
3. 实现 4 个方法：`name()`、`description()`、`parameters()`、`execute()`。
4. 工具名必须在所有工具中全局唯一，否则 Spring 启动时会报「工具名冲突」。
5. 重启项目后工具会自动注册到模型工具列表和系统提示词中。

## 示例

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
