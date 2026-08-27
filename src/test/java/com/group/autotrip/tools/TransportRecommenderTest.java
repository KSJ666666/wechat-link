package com.group.autotrip.tools;

import com.group.autotrip.common.model.RouteOption;
import com.group.autotrip.common.model.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRecommenderTest {

    private FakeAmapService amap;
    private CityTransportSupport support;

    @BeforeEach
    void setUp() {
        amap = new FakeAmapService();
        support = new CityTransportSupport();
    }

    @Test
    void shortSameCityRecommendsWalking() throws Exception {
        amap.poi("西湖", "杭州");
        amap.poi("灵隐寺", "杭州");
        amap.driving(600, 180);
        amap.walking(650, 480);
        amap.transit(List.of(option(TransportMode.BUS, 900, 600)));

        TransportRecommender.Recommendation result = recommender(false)
                .recommend("西湖", "灵隐寺", "杭州", "杭州", null, null);

        assertEquals(TransportMode.WALKING, result.recommended().mode());
        assertTrue(result.reason().contains("步行"));
    }

    @Test
    void straightLineDistanceEstimatesKm() {
        AmapService.PoiInfo hangzhou = poiAt("杭州", "杭州市", "120.15,30.28");
        AmapService.PoiInfo huangshan = poiAt("黄山", "黄山市", "118.14,29.71");
        assertTrue(TransportRecommender.straightLineKm(hangzhou, huangshan) > 80,
                "杭州到黄山直线距离应超过 80 公里");
        AmapService.PoiInfo near = poiAt("B", "北京", "116.41,39.92");
        AmapService.PoiInfo a = poiAt("A", "北京", "116.40,39.91");
        assertTrue(TransportRecommender.straightLineKm(a, near) < 80, "近点距离应远小于 80 公里");
        AmapService.PoiInfo noLocation = poiAt("X", "北京", "");
        assertEquals(-1.0, TransportRecommender.straightLineKm(noLocation, near), 0.0001,
                "坐标缺失应返回 -1");
    }

    @Test
    void walkingSkippedForLongDistanceRoutes() throws Exception {
        amap.poiAt("杭州", "杭州市", "120.15,30.28");
        amap.poiAt("黄山", "黄山市", "118.14,29.71");
        amap.driving(250_000, 12_000);
        amap.transit(List.of(option(TransportMode.RAIL, 200_000, 3_600)));

        TransportRecommender.Recommendation result = recommender(false)
                .recommend("杭州", "黄山", "杭州", "黄山", null, null);

        assertEquals(0, amap.walkingCalls, "超限远距离不应发起步行规划请求");
        assertNotEquals(null, result.recommended());
    }

    @Test
    void walkingStillCalledForShortRoutes() throws Exception {
        amap.poiAt("A", "北京", "116.40,39.91");
        amap.poiAt("B", "北京", "116.41,39.92");
        amap.walking(1_200, 900);
        amap.driving(1_500, 300);

        recommender(false).recommend("A", "B", "北京", "北京", null, null);

        assertEquals(1, amap.walkingCalls, "近距离路线应正常调用步行规划");
    }

    private static AmapService.PoiInfo poiAt(String name, String city, String lngLat) {
        return new AmapService.PoiInfo("id-" + name, name, "", "", city, "", "", lngLat);
    }

    @Test
    void rushHourRecommendsMetroInMetroCity() throws Exception {
        amap.poi("国贸", "北京");
        amap.poi("颐和园", "北京");
        amap.driving(8000, 1200);
        amap.walking(9000, 7200);
        amap.transit(List.of(
                option(TransportMode.BUS, 9000, 3000),
                option(TransportMode.METRO, 9000, 2400)));

        TransportRecommender.Recommendation result = recommender(true)
                .recommend("国贸", "颐和园", "北京", "北京", null, null);

        assertEquals(TransportMode.METRO, result.recommended().mode());
        assertTrue(result.reason().contains("高峰"));
    }

    @Test
    void noMetroCityFallsBackToBus() throws Exception {
        amap.poi("布达拉宫", "拉萨");
        amap.poi("大昭寺", "拉萨");
        amap.driving(5000, 600);
        amap.walking(5200, 3600);
        amap.transit(List.of(option(TransportMode.BUS, 5200, 1800)));

        TransportRecommender.Recommendation result = recommender(true)
                .recommend("布达拉宫", "大昭寺", "拉萨", "拉萨", null, null);

        assertEquals(TransportMode.BUS, result.recommended().mode());
    }

    @Test
    void farCrossCityRecommendsRail() throws Exception {
        amap.poi("北京南站", "北京");
        amap.poi("上海虹桥站", "上海");
        amap.driving(1_200_000, 43_200);
        amap.walking(1_200_000, 900_000);
        amap.transit(List.of(option(TransportMode.RAIL, 1_300_000, 15_000)));

        TransportRecommender.Recommendation result = recommender(false)
                .recommend("北京南站", "上海虹桥站", "北京", "上海", null, null);

        assertEquals(TransportMode.RAIL, result.recommended().mode());
        assertTrue(result.reason().contains("高铁"));
    }

    @Test
    void avoidDrivingExcludesDrivingOption() throws Exception {
        amap.poi("国贸", "北京");
        amap.poi("西单", "北京");
        amap.driving(6000, 1200);
        amap.walking(6200, 4800);
        amap.transit(List.of(
                option(TransportMode.BUS, 6100, 2400),
                option(TransportMode.METRO, 6000, 1800)));

        TransportRecommender.Recommendation result = recommender(false)
                .recommend("国贸", "西单", "北京", "北京", null, "avoid_driving");

        assertNotEquals(TransportMode.DRIVING, result.recommended().mode());
        assertTrue(result.alternatives().stream()
                .noneMatch(o -> o.mode() == TransportMode.DRIVING));
    }

    @Test
    void specificModeReturnsOnlyThatMode() throws Exception {
        amap.poi("国贸", "北京");
        amap.poi("西单", "北京");
        amap.driving(6000, 1200);
        amap.walking(6200, 4800);
        amap.transit(List.of(
                option(TransportMode.BUS, 6100, 2400),
                option(TransportMode.METRO, 6000, 1800)));

        TransportRecommender.Recommendation result = recommender(false)
                .recommend("国贸", "西单", "北京", "北京", "metro", null);

        assertEquals(TransportMode.METRO, result.recommended().mode());
        assertTrue(result.alternatives().isEmpty());
    }

    @Test
    void citySupportRecognizesSuffixAndPinyin() {
        assertTrue(support.hasMetro("北京市"));
        assertTrue(support.hasMetro("beijing"));
        assertFalse(support.hasMetro("拉萨"));
        assertTrue(support.isSameCity("北京市", "北京"));
    }

    private TransportRecommender recommender(boolean rushHour) {
        return new TransportRecommender(amap, support) {
            @Override
            protected boolean isRushHour() {
                return rushHour;
            }
        };
    }

    private static RouteOption option(TransportMode mode, long distanceMeters, long durationSeconds) {
        return new RouteOption(mode, "A", "B", distanceMeters, durationSeconds, "", mode.displayName());
    }

    private static final class FakeAmapService extends AmapService {
        private final Map<String, PoiInfo> pois = new HashMap<>();
        private RouteOption driving;
        private RouteOption walking;
        private List<RouteOption> transits = List.of();
        private int walkingCalls;

        void poi(String name, String city) {
            pois.put(name, new PoiInfo("id-" + name, name, "", "", city, "", "", "116.0,39.9"));
        }

        void poiAt(String name, String city, String lngLat) {
            pois.put(name, new PoiInfo("id-" + name, name, "", "", city, "", "", lngLat));
        }

        void driving(long distanceMeters, long durationSeconds) {
            driving = option(TransportMode.DRIVING, distanceMeters, durationSeconds);
        }

        void walking(long distanceMeters, long durationSeconds) {
            walking = option(TransportMode.WALKING, distanceMeters, durationSeconds);
        }

        void transit(List<RouteOption> options) {
            transits = options;
        }

        @Override
        public PoiInfo resolvePoi(String keyword, String city) throws IOException {
            PoiInfo poi = pois.get(keyword);
            if (poi == null) {
                throw new IOException("未找到地点：" + keyword);
            }
            return poi;
        }

        @Override
        public RouteOption getDrivingRouteOption(PoiInfo from, PoiInfo to) throws IOException {
            return requireOption(driving, "驾车");
        }

        @Override
        public RouteOption getWalkingRoute(PoiInfo from, PoiInfo to) throws IOException {
            walkingCalls++;
            return requireOption(walking, "步行");
        }

        @Override
        public List<RouteOption> getTransitRoutes(PoiInfo from, PoiInfo to, String city, String cityd) throws IOException {
            return transits;
        }

        private static RouteOption requireOption(RouteOption option, String name) throws IOException {
            if (option == null) {
                throw new IOException("未配置" + name + "路线");
            }
            return option;
        }
    }
}
