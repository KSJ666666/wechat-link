package com.group.autotrip.tools;

import com.group.autotrip.common.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function Calling 工具注册表。
 *
 * <p>Spring 自动收集所有 {@link FunctionTool} 实现（{@code @Component}），这里按名字分发执行。
 * 新增工具 = 新增一个 {@code @Component} 实现类，无需修改本类或任何公共代码。
 */
@Component
public class CustomTools {

    private final Map<String, FunctionTool> tools;

    public CustomTools(List<FunctionTool> toolList) {
        Map<String, FunctionTool> map = new LinkedHashMap<>();
        for (FunctionTool tool : toolList) {
            FunctionTool prev = map.putIfAbsent(tool.name(), tool);
            if (prev != null) {
                throw new IllegalStateException("工具名冲突：" + tool.name()
                        + "（" + prev.getClass().getSimpleName() + " 与 " + tool.getClass().getSimpleName() + "）");
            }
        }
        this.tools = Collections.unmodifiableMap(map);
    }

    /** 按工具名分发执行，返回给模型的文本结果。 */
    public String execute(String name, JsonNode args) throws Exception {
        FunctionTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具：" + name);
        }
        return tool.execute(args);
    }

    /** 全部已注册工具（用于生成工具列表与系统提示词） */
    public Collection<FunctionTool> all() {
        return tools.values();
    }
}
