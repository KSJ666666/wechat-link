package com.group.autotrip.tools;

import com.group.autotrip.common.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/** 工具 2：获取当前日期/时间/星期。参数：format（可选，datetime/date/time/weekday） */
@Component
public class GetTimeTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "get_time";
    }

    @Override
    public String description() {
        return "获取当前日期、时间或星期几。当用户询问现在几点、今天几号、星期几时调用。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode fmt = p.putObject("properties").putObject("format");
        fmt.put("type", "string");
        fmt.put("description", "返回格式：datetime=日期时间、date=日期、time=时间、weekday=星期几");
        ArrayNode fmts = fmt.putArray("enum");
        fmts.add("datetime").add("date").add("time").add("weekday");
        fmt.put("default", "datetime");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) {
        String format = args.path("format").asText("datetime");
        LocalDateTime now = LocalDateTime.now();
        return switch (format) {
            case "date" -> LocalDate.now().toString();
            case "time" -> now.toLocalTime().withNano(0).toString();
            case "weekday" -> now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
            default -> now.withNano(0).toString().replace('T', ' ');
        };
    }
}
