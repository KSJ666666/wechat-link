package com.group.autotrip.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagRouterTest {

    private final RagRouter router = new RagRouter();

    @Test
    void matchesCityPlusIntent() {
        assertEquals("杭州", router.match("杭州有哪些必去景点"));
        assertEquals("杭州", router.match("杭州旅游攻略"));
        assertEquals("大理", router.match("大理古城开放时间"));
        assertEquals("长沙", router.match("长沙有什么好玩的"));
        assertEquals("上海", router.match("上海外滩值得去吗"));
    }

    @Test
    void cityWithoutIntentNotRouted() {
        assertEquals("", router.match("杭州天气怎么样"));
        assertEquals("", router.match("从杭州到黄山三天自驾"));
        assertEquals("", router.match("上海今天限行吗"));
    }

    @Test
    void intentWithoutGuideCityNotRouted() {
        assertEquals("", router.match("北京有哪些必去景点"));
        assertEquals("", router.match("推荐几个好玩的地方"));
    }

    @Test
    void blankOrNullNotRouted() {
        assertEquals("", router.match(""));
        assertEquals("", router.match(null));
    }
}
