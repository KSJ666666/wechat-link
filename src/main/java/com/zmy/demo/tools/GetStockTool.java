package com.zmy.demo.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zmy.demo.stock.StockService;
import org.springframework.stereotype.Component;

/** 工具 3：查询股票实时行情（真实数据，东方财富免费接口）。参数：symbol（股票代码） */
@Component
public class GetStockTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StockService stockService;

    public GetStockTool(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public String name() {
        return "get_stock";
    }

    @Override
    public String description() {
        return "查询股票实时行情。当用户询问股价、涨跌时调用。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        p.putObject("properties").putObject("symbol")
                .put("type", "string").put("description", "股票代码，如 AAPL、600519");
        ArrayNode req = p.putArray("required");
        req.add("symbol");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String symbol = args.path("symbol").asText("");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 参数不能为空");
        }
        return stockService.getQuote(symbol).toString();
    }
}
