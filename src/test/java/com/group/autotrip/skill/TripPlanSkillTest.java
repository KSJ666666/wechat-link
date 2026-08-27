package com.group.autotrip.skill;

import com.group.autotrip.agent.TripPlanStore;
import com.group.autotrip.common.model.PlanStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripPlanSkillTest {

    private static TripPlanSkill newSkill() {
        return new TripPlanSkill(new TripPlanStore(null));
    }

    @Test
    void supportsPlanningAndReplanWordsButNotGuideQuestions() {
        TripPlanSkill skill = newSkill();
        assertTrue(skill.supports("从杭州到黄山三天自驾"));
        assertTrue(skill.supports("帮我规划行程"));
        assertTrue(skill.supports("去上海玩五天"));
        assertTrue(skill.supports("第二天太赶了，重新排"));
        assertFalse(skill.supports("杭州旅游攻略"));
        assertFalse(skill.supports("郑州天气怎么样"));
        assertFalse(skill.supports("杭州有哪些必去景点"));
        assertFalse(skill.supports(null));
    }

    @Test
    void regexParsesFromToDaysStyle() {
        TripPlanSkill.ParsedTripRequest request =
                TripPlanSkill.ParsedTripRequest.from("从杭州到黄山三天自驾，轻松一点");
        assertEquals("杭州", request.origin());
        assertEquals("黄山", request.destination());
        assertEquals(3, request.days());
        assertEquals("轻松", request.style());
    }

    @Test
    void regexParsesDestinationOnly() {
        TripPlanSkill.ParsedTripRequest request =
                TripPlanSkill.ParsedTripRequest.from("去上海玩五天");
        assertEquals("上海", request.destination());
        assertEquals(5, request.days());
    }

    @Test
    void extractsNumberedItemNames() {
        List<String> names = TripPlanSkill.extractItemNames(
                "1. 西湖，5A级\n2. 灵隐寺（热门）\n3. 雷峰塔，4.8分", 3);
        assertEquals(List.of("西湖", "灵隐寺", "雷峰塔"), names);
    }

    @Test
    void guessesRoadNames() {
        assertEquals("北四环中路", TripPlanSkill.guessRoad("顺便看看北四环中路堵不堵"));
        assertEquals("", TripPlanSkill.guessRoad("没有路名"));
    }

    @Test
    void fallbackItineraryBuildsDaysAndTitle() {
        TripPlanSkill.ParsedTripRequest request =
                new TripPlanSkill.ParsedTripRequest("杭州", "黄山", 3, "轻松", "杭州");
        TripPlanSkill.ToolData data = new TripPlanSkill.ToolData("", "", "", "", "", List.of(), "");
        var itinerary = TripPlanSkill.fallbackItinerary(request, data);
        assertEquals(3, itinerary.days().size());
        assertTrue(itinerary.title().contains("黄山"));
        assertEquals(PlanStatus.DRAFT, itinerary.status());
    }

    @Test
    void extractsFirstJsonObject() {
        assertEquals("{\"a\":1}", TripPlanSkill.firstJsonObject("好的：```json\n{\"a\":1}\n```"));
        assertNull(TripPlanSkill.firstJsonObject("没有 JSON"));
        assertNull(TripPlanSkill.firstJsonObject(null));
    }
}
