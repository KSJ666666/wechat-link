package com.group.autotrip.skill;

import com.group.autotrip.common.model.AlertType;
import com.group.autotrip.common.model.MonitorTarget;
import com.group.autotrip.monitor.MonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripGuardSkillTest {

    private static ObjectProvider<MonitorService> providerOf(MonitorService service) {
        return new ObjectProvider<>() {
            @Override
            public MonitorService getObject(Object... args) throws BeansException {
                return service;
            }

            @Override
            public MonitorService getIfAvailable() throws BeansException {
                return service;
            }

            @Override
            public MonitorService getIfUnique() throws BeansException {
                return service;
            }

            @Override
            public MonitorService getObject() throws BeansException {
                return service;
            }

            @Override
            public Iterator<MonitorService> iterator() {
                return List.of(service).iterator();
            }
        };
    }

    @Test
    void supportsMatchesGuardKeywords() {
        TripGuardSkill skill = new TripGuardSkill(providerOf(new MonitorService(null, null, null)));
        assertTrue(skill.supports("帮我监控郑州天气"));
        assertTrue(skill.supports("行程护航"));
        assertTrue(skill.supports("低于0度提醒我"));
        assertTrue(skill.supports("帮我盯着大理天气"));
        assertFalse(skill.supports("郑州天气怎么样"));
        assertFalse(skill.supports(null));
    }

    @Test
    void cancelCommandRemovesAllTargets() throws Exception {
        MonitorService service = new MonitorService(null, null, null);
        service.add("u1", new MonitorTarget("郑州天气", AlertType.WEATHER, "郑州", "低于0度"));
        TripGuardSkill skill = new TripGuardSkill(providerOf(service));

        String reply = skill.execute("取消监控", new SkillContext("u1", "取消监控", null, null));

        assertTrue(reply.contains("已取消"));
        assertTrue(service.list("u1").isEmpty());
    }

    @Test
    void cancelWithNoTargetsReportsEmpty() throws Exception {
        MonitorService service = new MonitorService(null, null, null);
        TripGuardSkill skill = new TripGuardSkill(providerOf(service));

        String reply = skill.execute("取消监控", new SkillContext("u1", "取消监控", null, null));

        assertTrue(reply.contains("没有进行中"));
    }

    @Test
    void listCommandShowsTargetsWithTypeAndRule() throws Exception {
        MonitorService service = new MonitorService(null, null, null);
        service.add("u1", new MonitorTarget("郑州天气", AlertType.WEATHER, "郑州", "低于0度提醒"));
        TripGuardSkill skill = new TripGuardSkill(providerOf(service));

        String reply = skill.execute("查看监控", new SkillContext("u1", "查看监控", null, null));

        assertTrue(reply.contains("郑州天气"));
        assertTrue(reply.contains("[天气]"));
        assertTrue(reply.contains("低于0度提醒"));
    }

    @Test
    void listWithNoTargetsShowsUsageHint() throws Exception {
        TripGuardSkill skill = new TripGuardSkill(providerOf(new MonitorService(null, null, null)));

        String reply = skill.execute("查看监控", new SkillContext("u1", "查看监控", null, null));

        assertTrue(reply.contains("没有进行中"));
    }

    @Test
    void heuristicFallsBackWithoutLlm() {
        assertEquals(AlertType.WEATHER,
                TripGuardSkill.heuristicTarget("监控郑州天气低于0度就提醒").type());
        assertEquals(AlertType.TRAFFIC,
                TripGuardSkill.heuristicTarget("监控北四环中路路况拥堵提醒").type());
        assertEquals(AlertType.TIME,
                TripGuardSkill.heuristicTarget("提醒我9点出发").type());
        assertEquals(AlertType.BUDGET,
                TripGuardSkill.heuristicTarget("预算超支就提醒").type());
    }

    @Test
    void firstJsonObjectExtractsFromNoisyReply() {
        assertNull(TripGuardSkill.firstJsonObject(null));
        assertNull(TripGuardSkill.firstJsonObject("没有 JSON"));
        assertEquals("{\"name\":\"a\"}",
                TripGuardSkill.firstJsonObject("好的，输出如下：```json\n{\"name\":\"a\"}\n```"));
    }
}
