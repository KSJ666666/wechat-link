package com.group.autotrip.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Function Calling 完整闭环演示（教学示例）。
 *
 * <p>需要真实调用阿里云百炼接口，因此：
 * <ul>
 *   <li>设置了环境变量 DASHSCOPE_API_KEY 时才执行；</li>
 *   <li>未设置时测试自动跳过，不影响 mvn test。</li>
 * </ul>
 *
 * <p>运行：mvn test -Dtest=FunctionCallingDemoTest
 */
@SpringBootTest(properties = "wechat.auto-login=false")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class FunctionCallingDemoTest {

    @Autowired
    private DashScopeService dashScopeService;

    /**
     * 演示 calculate 工具 + 多轮闭环：
     * (15 + 3) * 2 需要先算加法、再算乘法。模型可能在同一轮并行发起多个工具调用，也可能分多轮串行调用，闭环都能正确处理。
     */
    @Test
    void calculatorToolWithMultiRoundLoop() throws Exception {
        String reply = dashScopeService.chatWithTools("请帮我计算 (15 + 3) * 2 等于多少？");
        System.out.println(">>> 模型最终回复：" + reply);
        assertTrue(reply.contains("36"), "回复应包含计算结果 36，实际：" + reply);
    }

    /** 演示 get_time 工具：模型调用工具拿到真实时间后再组织成自然语言回复 */
    @Test
    void timeTool() throws Exception {
        String reply = dashScopeService.chatWithTools("现在几点了？用中文回答");
        System.out.println(">>> 模型最终回复：" + reply);
        assertTrue(reply.contains("点") || reply.contains("分") || reply.contains(":"),
                "回复应包含日期/时间信息：" + reply);
    }

    /** 机器人路径（WeChatService 实际调用 chatOrGenerate）：必须调 calculate 的多步算式 */
    @Test
    void botPathMathViaChatOrGenerate() throws Exception {
        DashScopeService.ChatResult result = dashScopeService.chatOrGenerate("请帮我计算 (15 + 3) * 2 等于多少？");
        System.out.println(">>> 机器人路径回复：" + result.text());
        assertTrue(result.text().contains("36"), "回复应包含计算结果 36，实际：" + result.text());
    }

    /** 机器人路径：问时间必须调 get_time（模型不可能凭训练知识答对） */
    @Test
    void botPathTimeViaChatOrGenerate() throws Exception {
        DashScopeService.ChatResult result = dashScopeService.chatOrGenerate("现在几点了？");
        System.out.println(">>> 机器人路径回复：" + result.text());
        assertTrue(result.text().contains("点") || result.text().contains("分") || result.text().contains(":"),
                "回复应包含时间信息：" + result.text());
    }

    /** 教学演示路径：问股价必须调 get_stock */
    @Test
    void stockTool() throws Exception {
        String reply = dashScopeService.chatWithTools("苹果公司的股票现在多少钱？");
        System.out.println(">>> 模型最终回复：" + reply);
        assertTrue(reply.contains("AAPL"), "回复应包含股票代码，实际：" + reply);
    }


    /** 工具回答不了 → 联网搜索兜底（enableSearch=true 时） */
    @Test
    void botPathSearchFallbackWhenNoTool() throws Exception {
        DashScopeService.ChatResult result = dashScopeService.chatOrGenerate("今天有什么热点新闻？");
        System.out.println(">>> 搜索兜底回复：" + result.text());
        assertTrue(result.text() != null && !result.text().isBlank(), "搜索兜底回复不应为空");
    }
    /** 机器人路径（chatOrGenerate）：问股价必须调 get_stock */
    @Test
    void botPathStockViaChatOrGenerate() throws Exception {
        DashScopeService.ChatResult result = dashScopeService.chatOrGenerate("苹果公司的股票现在多少钱？");
        System.out.println(">>> 机器人路径回复：" + result.text());
        assertTrue(result.text().contains("AAPL"), "回复应包含股票代码，实际：" + result.text());
    }
}