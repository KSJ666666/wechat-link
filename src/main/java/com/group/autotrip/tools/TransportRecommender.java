package com.group.autotrip.tools;

import com.group.autotrip.common.model.RouteOption;
import com.group.autotrip.common.model.TransportMode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

@Service
public class TransportRecommender {

    private static final long WALKING_LIMIT_METERS = 1_000;
    private static final long NEAR_LIMIT_METERS = 3_000;
    private static final long MIDDLE_LIMIT_METERS = 10_000;
    private static final long LONG_LIMIT_METERS = 30_000;
    private static final long CROSS_CITY_RAIL_LIMIT_METERS = 100_000;

    private final AmapService amapService;
    private final CityTransportSupport citySupport;

    public TransportRecommender(AmapService amapService, CityTransportSupport citySupport) {
        this.amapService = amapService;
        this.citySupport = citySupport;
    }

    public Recommendation recommend(String origin, String destination, String originCity, String destinationCity, String mode, String prefer) throws IOException {
        AmapService.PoiInfo from = amapService.resolvePoi(origin, originCity);
        AmapService.PoiInfo to = amapService.resolvePoi(destination, destinationCity);
        boolean sameCity = citySupport.isSameCity(from.city(), to.city());
        String city = citySupport.cityCode(from.city());
        if (city.isBlank()) city = originCity;
        String cityd = citySupport.cityCode(to.city());
        if (cityd.isBlank()) cityd = destinationCity;

        TransportMode requested = parseMode(mode);
        boolean avoidDriving = "avoid_driving".equalsIgnoreCase(prefer);
        if (requested != null) {
            RouteOption option = fetch(requested, from, to, city, cityd);
            return new Recommendation(from.name(), to.name(), sameCity, option.distanceMeters(), option, List.of(), "按你选择的" + requested.displayName() + "方式查询");
        }

        List<RouteOption> options = new ArrayList<>();
        if (!avoidDriving) addQuietly(options, () -> List.of(amapService.getDrivingRouteOption(from, to)));
        addQuietly(options, () -> List.of(amapService.getWalkingRoute(from, to)));
        if (!city.isBlank() && !cityd.isBlank()) addQuietly(options, () -> amapService.getTransitRoutes(from, to, city, cityd));
        if (options.isEmpty()) throw new IOException("未查询到可用交通方式，请确认地点名称或稍后重试");

        EnumMap<TransportMode, RouteOption> byMode = new EnumMap<>(TransportMode.class);
        for (RouteOption option : options) {
            RouteOption prev = byMode.get(option.mode());
            if (prev == null || option.durationSeconds() < prev.durationSeconds()) {
                byMode.put(option.mode(), option);
            }
        }
        List<RouteOption> unique = new ArrayList<>(byMode.values());
        unique.sort(Comparator.comparingLong(RouteOption::durationSeconds));

        long baseline = 0;
        for (RouteOption option : unique) baseline = Math.max(baseline, option.distanceMeters());
        Selection selection = select(unique, sameCity, baseline, prefer, from.city(), to.city());
        List<RouteOption> alternatives = new ArrayList<>(unique);
        alternatives.remove(selection.option());
        return new Recommendation(from.name(), to.name(), sameCity, baseline, selection.option(), alternatives, selection.reason());
    }

    private RouteOption fetch(TransportMode mode, AmapService.PoiInfo from, AmapService.PoiInfo to, String city, String cityd) throws IOException {
        return switch (mode) {
            case WALKING -> amapService.getWalkingRoute(from, to);
            case DRIVING -> amapService.getDrivingRouteOption(from, to);
            case BUS, METRO, RAIL -> {
                List<RouteOption> transits = amapService.getTransitRoutes(from, to, city, cityd);
                RouteOption match = findMode(transits, mode);
                if (match == null) throw new IOException("未查询到" + mode.displayName() + "方案");
                yield match;
            }
        };
    }

    private Selection select(List<RouteOption> options, boolean sameCity, long baseline, String prefer, String fromCity, String toCity) {
        RouteOption preferred = findPreference(options, prefer);
        if (preferred != null) return new Selection(preferred, "按你的偏好，推荐" + preferred.mode().displayName());

        RouteOption walking = findMode(options, TransportMode.WALKING);
        RouteOption bus = findMode(options, TransportMode.BUS);
        RouteOption metro = findMode(options, TransportMode.METRO);
        RouteOption driving = findMode(options, TransportMode.DRIVING);
        RouteOption rail = findMode(options, TransportMode.RAIL);
        boolean metroCity = citySupport.hasMetro(fromCity) || citySupport.hasMetro(toCity);

        if (sameCity) {
            if (baseline <= WALKING_LIMIT_METERS && walking != null) return new Selection(walking, "距离很近，步行最方便");
            if (baseline <= NEAR_LIMIT_METERS) {
                if (walking != null && (bus == null || walking.durationSeconds() <= bus.durationSeconds() * 1.3)) return new Selection(walking, "距离较近，步行和公交都很方便");
                if (bus != null) return new Selection(bus, "距离较近，公交更省力");
                if (walking != null) return new Selection(walking, "距离较近，步行最方便");
            }
            if (baseline <= MIDDLE_LIMIT_METERS) {
                if (metro != null && (isRushHour() || metroCity)) return new Selection(metro, isRushHour() ? "高峰期地铁更稳妥" : "该城市地铁方便，建议地铁");
                if (bus != null) return new Selection(bus, "距离适中，公交性价比高");
                if (driving != null) return new Selection(driving, "距离适中，驾车更快");
                return new Selection(options.get(0), "");
            }
            if (baseline <= LONG_LIMIT_METERS) {
                if (metro != null && (isRushHour() || metroCity)) return new Selection(metro, "距离较远，地铁更省心");
                if (driving != null) return new Selection(driving, "距离较远，驾车更方便");
                if (metro != null) return new Selection(metro, "距离较远，建议地铁");
                return new Selection(options.get(0), "");
            }
            if (driving != null) return new Selection(driving, "距离较远，驾车更灵活");
            return new Selection(options.get(0), "");
        }
        if (baseline >= CROSS_CITY_RAIL_LIMIT_METERS && rail != null) return new Selection(rail, "跨城距离较远，高铁/火车更快");
        if (driving != null) return new Selection(driving, rail != null ? "距离较近，驾车更方便，也可选择高铁/火车" : "跨城出行，驾车更方便");
        if (rail != null) return new Selection(rail, "跨城出行，高铁/火车更合适");
        return new Selection(options.get(0), "");
    }

    private RouteOption findPreference(List<RouteOption> options, String prefer) {
        if (prefer == null || prefer.isBlank() || "avoid_driving".equalsIgnoreCase(prefer) || "avoid-driving".equalsIgnoreCase(prefer)) return null;
        TransportMode mode;
        try {
            mode = parseMode(prefer);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return mode == null ? null : findMode(options, mode);
    }

    private static RouteOption findMode(List<RouteOption> options, TransportMode mode) {
        for (RouteOption option : options) if (option.mode() == mode) return option;
        return null;
    }

    private static TransportMode parseMode(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "walking" -> TransportMode.WALKING;
            case "bus" -> TransportMode.BUS;
            case "metro" -> TransportMode.METRO;
            case "driving" -> TransportMode.DRIVING;
            case "rail" -> TransportMode.RAIL;
            default -> throw new IllegalArgumentException("不支持的交通方式：" + value);
        };
    }

    private void addQuietly(List<RouteOption> options, RouteSupplier supplier) {
        try {
            List<RouteOption> result = supplier.get();
            if (result != null) options.addAll(result);
        } catch (IOException ignored) {
        }
    }

    protected boolean isRushHour() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int hour = now.getHour();
        return (hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 19);
    }

    private interface RouteSupplier { List<RouteOption> get() throws IOException; }
    private record Selection(RouteOption option, String reason) {}
    public record Recommendation(String originName, String destinationName, boolean sameCity, long baselineDistanceMeters, RouteOption recommended, List<RouteOption> alternatives, String reason) {}
}
