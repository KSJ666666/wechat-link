package com.group.autotrip.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmapServiceTest {

    @Test
    void statusBySpeedMapsCongestionLevels() {
        assertEquals("未知", AmapService.statusBySpeed(0));
        assertEquals("未知", AmapService.statusBySpeed(-5));
        assertEquals("严重拥堵", AmapService.statusBySpeed(3.2));
        assertEquals("严重拥堵", AmapService.statusBySpeed(5.99));
        assertEquals("拥堵", AmapService.statusBySpeed(6));
        assertEquals("拥堵", AmapService.statusBySpeed(11.9));
        assertEquals("缓行", AmapService.statusBySpeed(12));
        assertEquals("缓行", AmapService.statusBySpeed(21.9));
        assertEquals("畅通", AmapService.statusBySpeed(22));
        assertEquals("畅通", AmapService.statusBySpeed(45));
    }
}
