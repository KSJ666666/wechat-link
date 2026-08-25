package com.group.autotrip.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票行情服务测试（真实调用东方财富免费接口，需要联网）。
 */
@SpringBootTest(properties = "wechat.auto-login=false")
class StockServiceTest {

    @Autowired
    private StockService stockService;

    /** A股：600519 贵州茅台 */
    @Test
    void aShareQuote() throws Exception {
        StockService.StockInfo info = stockService.getQuote("600519");
        System.out.println(">>> A股：" + info);
        assertTrue(info.name().contains("茅台"), "名称应包含茅台，实际：" + info.name());
        assertTrue(info.price() > 0, "价格应大于 0");
        assertTrue("元".equals(info.unit()), "A股单位应为元");
    }

    /** 美股：AAPL 苹果 */
    @Test
    void usQuote() throws Exception {
        StockService.StockInfo info = stockService.getQuote("AAPL");
        System.out.println(">>> 美股：" + info);
        assertTrue("AAPL".equals(info.symbol()), "代码应为 AAPL");
        assertTrue(info.price() > 0, "价格应大于 0");
        assertTrue("美元".equals(info.unit()), "美股单位应为美元");
    }
}