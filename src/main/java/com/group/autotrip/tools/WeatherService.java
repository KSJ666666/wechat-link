package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询服务（对接心知天气 Seniverse 实时天气接口）。
 * <p>
 * 需在 application.properties 中配置：
 * <pre>
 * weather.api-key=${WEATHER_API_KEY:}
 * </pre>
 * location 参数支持城市中文名（如"郑州"）、拼音、城市 ID 或经纬度。
 */

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private static final String NOW_URL = "https://api.seniverse.com/v3/weather/now.json";
    private static final String DAILY_URL = "https://api.seniverse.com/v3/weather/daily.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    @Value("${weather.api-key:}")
    private String apiKey;

    @Value("${weather.language:zh-Hans}")
    private String language;

    @Value("${weather.unit:c}")
    private String unit;

    /**
     * 获取实时天气。
     *
     * @param location 城市中文名、拼音、城市 ID 或经纬度
     * @return 实时天气信息
     */
    public WeatherInfo getNowWeather(String location) throws IOException {
        requireKey();
        String url = NOW_URL + "?key=" + apiKey
                + "&location=" + location
                + "&language=" + language
                + "&unit=" + unit;

        JsonNode resp = getJson(url);
        JsonNode results = resp.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IOException("天气接口返回为空: " + resp);
        }

        JsonNode result = results.get(0);
        JsonNode now = result.path("now");
        JsonNode loc = result.path("location");

        return new WeatherInfo(
                loc.path("name").asText(""),
                loc.path("path").asText(""),
                now.path("text").asText(""),
                now.path("code").asText(""),
                now.path("temperature").asText(""),
                result.path("last_update").asText("")
        );
    }

    public List<DailyForecast> getDailyWeather(String location, int days) throws IOException {
        requireKey();
        String url = DAILY_URL + "?key=" + apiKey
                + "&location=" + location
                + "&language=" + language
                + "&unit=" + unit
                + "&days=" + Math.min(Math.max(days, 1), 15);

        JsonNode resp = getJson(url);
        JsonNode results = resp.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IOException("天气接口返回为空: " + resp);
        }

        JsonNode result = results.get(0);
        JsonNode loc = result.path("location");
        JsonNode daily = result.path("daily");
        if (!daily.isArray() || daily.isEmpty()) {
            throw new IOException("天气预报接口返回为空: " + resp);
        }

        List<DailyForecast> forecasts = new ArrayList<>();
        for (JsonNode day : daily) {
            forecasts.add(new DailyForecast(
                    loc.path("name").asText(""),
                    day.path("date").asText(""),
                    day.path("text_day").asText(""),
                    day.path("text_night").asText(""),
                    day.path("high").asText(""),
                    day.path("low").asText("")
            ));
        }
        return forecasts;
    }

    private void requireKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException();
        }
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + text);
            }
            return mapper.readTree(text);
        }
    }

    /** 实时天气信息 */
    public record WeatherInfo(
            String city,
            String path,
            String text,
            String code,
            String temperature,
            String lastUpdate
    ) {
        @Override
        public String toString() {
            return String.format("%s 天气：%s，温度：%s°C（更新时间：%s）",
                    city, text, temperature, lastUpdate);
        }
    }

    public record DailyForecast(
            String city,
            String date,
            String textDay,
            String textNight,
            String high,
            String low
    ) {
        @Override
        public String toString() {
            return String.format("%s %s：%s转%s，最高%s°C，最低%s°C",
                    city, date, textDay, textNight, high, low);
        }
    }
}
