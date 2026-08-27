package com.group.autotrip.monitor;

import com.group.autotrip.common.model.AlertType;
import com.group.autotrip.common.model.MonitorTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorServiceTest {

    /** 注册表操作不依赖外部服务，构造时传空依赖即可 */
    private static MonitorService newService() {
        return new MonitorService(null, null, null);
    }

    private static MonitorTarget weather(String name) {
        return new MonitorTarget(name, AlertType.WEATHER, "郑州", "低于0度提醒");
    }

    @Test
    void registryIsolatedPerUser() {
        MonitorService service = newService();
        assertTrue(service.add("userA", weather("郑州天气")));
        assertTrue(service.add("userB", weather("郑州天气")));
        assertEquals(1, service.list("userA").size());
        assertEquals(1, service.list("userB").size());
        assertEquals(2, service.size());
    }

    @Test
    void duplicateNameInSameUserSkipped() {
        MonitorService service = newService();
        assertTrue(service.add("userA", weather("郑州天气")));
        assertFalse(service.add("userA", weather("郑州天气")));
        assertEquals(1, service.list("userA").size());
    }

    @Test
    void removeAllClearsOnlyThatUser() {
        MonitorService service = newService();
        service.add("userA", weather("郑州天气"));
        service.add("userB", new MonitorTarget("北四环路况", AlertType.TRAFFIC, "北京 北四环中路", "拥堵时提醒"));
        assertEquals(1, service.removeAll("userA"));
        assertEquals(0, service.removeAll("userA"));
        assertEquals(1, service.list("userB").size());
    }

    @Test
    void nullUserIdFallsIntoSharedBucket() {
        MonitorService service = newService();
        assertTrue(service.add(null, weather("郑州天气")));
        assertEquals(1, service.list(null).size());
        assertEquals(1, service.list("").size());
    }

    @Test
    void parseYesNoVariants() {
        assertTrue(MonitorService.parseYesNo("是"));
        assertTrue(MonitorService.parseYesNo("是，已触发"));
        assertFalse(MonitorService.parseYesNo("否"));
        assertFalse(MonitorService.parseYesNo("否，未触发"));
        assertFalse(MonitorService.parseYesNo(""));
        assertFalse(MonitorService.parseYesNo("不确定"));
        assertFalse(MonitorService.parseYesNo(null));
    }

    @Test
    void parseCityAndRoad() {
        assertArrayEquals(new String[]{"郑州", "北四环中路"},
                MonitorService.parseCityAndRoad("郑州 北四环中路"));
        assertArrayEquals(new String[]{"郑州", "北四环中路"},
                MonitorService.parseCityAndRoad("郑州,北四环中路"));
        assertArrayEquals(new String[]{"郑州", "北四环中路"},
                MonitorService.parseCityAndRoad("郑州、北四环中路"));
        assertNull(MonitorService.parseCityAndRoad("郑州"));
        assertNull(MonitorService.parseCityAndRoad(null));
    }
}
