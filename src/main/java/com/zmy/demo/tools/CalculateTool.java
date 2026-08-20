package com.zmy.demo.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 工具 1：四则运算计算器。参数：a、b（数字）、operator（+ - * /） */
@Component
public class CalculateTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "calculate";
    }

    @Override
    public String description() {
        return "计算两个数的四则运算结果。当用户要求做加、减、乘、除等数学计算时调用。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("a").put("type", "number").put("description", "第一个操作数");
        props.putObject("b").put("type", "number").put("description", "第二个操作数");
        ObjectNode op = props.putObject("operator");
        op.put("type", "string");
        op.put("description", "四则运算符");
        ArrayNode ops = op.putArray("enum");
        ops.add("+").add("-").add("*").add("/");
        ArrayNode req = p.putArray("required");
        req.add("a").add("b").add("operator");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) {
        BigDecimal a = new BigDecimal(args.path("a").asText());
        BigDecimal b = new BigDecimal(args.path("b").asText());
        String operator = args.path("operator").asText("");

        BigDecimal result = switch (operator) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            case "/" -> {
                if (b.signum() == 0) {
                    throw new IllegalArgumentException("除数不能为 0");
                }
                // 除法保留 10 位小数，避免循环小数
                yield a.divide(b, 10, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default -> throw new IllegalArgumentException("不支持的运算符：" + operator);
        };

        return a.stripTrailingZeros().toPlainString() + " " + operator + " "
                + b.stripTrailingZeros().toPlainString() + " = "
                + result.stripTrailingZeros().toPlainString();
    }
}
