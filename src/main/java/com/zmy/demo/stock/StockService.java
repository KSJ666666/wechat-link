package com.zmy.demo.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 股票实时行情服务（对接东方财富免费行情接口 push2.eastmoney.com，无需 key）。
 *
 * <p>接口返回 JSON，关键字段（注意数值精度）：
 * <ul>
 *   <li>f57 代码、f58 名称</li>
 *   <li>f43 最新价：A股 ×100、美股 ×1000，需按市场换算</li>
 *   <li>f60 昨收（精度同上）、f169 涨跌额（精度同上）</li>
 *   <li>f170 涨跌幅（×100，如 26 表示 0.26%）、f86 行情时间（unix 秒）</li>
 * </ul>
 *
 * <p>东方财富 secid = 市场编号.代码：A股沪市 1.、深市/北交所 0.；美股 105.(纳斯达克) / 106.(纽交所) / 107.(美交所)。
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private static final String QUOTE_URL = "https://push2.eastmoney.com/api/qt/stock/get";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 查询实时行情。按代码推断可能的市场，逐个尝试，取第一个有数据的。
     *
     * @param symbol 股票代码，如 600519（A股）或 AAPL（美股）
     * @return 实时行情
     */
    public StockInfo getQuote(String symbol) throws IOException {
        for (String secid : candidateSecids(symbol)) {
            StockInfo info = tryQuote(secid);
            if (info != null) {
                log.info("股票行情 {} -> {}", symbol, info);
                return info;
            }
        }
        throw new IOException("未找到股票行情: " + symbol);
    }

    private StockInfo tryQuote(String secid) throws IOException {
        String url = QUOTE_URL + "?secid=" + secid + "&fields=f43,f57,f58,f60,f169,f170,f86";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            JsonNode data = mapper.readTree(response.body().string()).path("data");
            // 该市场没有这只股票时 data 为 null，或最新价为 0
            if (data.isMissingNode() || data.isNull() || data.path("f43").asDouble(0) == 0) {
                return null;
            }
            // A股价格 2 位小数（×100），美股 3 位小数（×1000）
            double scale = secid.startsWith("1.") || secid.startsWith("0.") ? 100.0 : 1000.0;
            String unit = scale == 100.0 ? "元" : "美元";

            long ts = data.path("f86").asLong();
            String time = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            return new StockInfo(
                    data.path("f57").asText(""),
                    data.path("f58").asText(""),
                    data.path("f43").asDouble() / scale,
                    data.path("f60").asDouble() / scale,
                    data.path("f169").asDouble() / scale,
                    data.path("f170").asDouble() / 100.0,
                    unit,
                    time);
        }
    }

    /** 根据代码推断可能的 secid 列表（按可能性排序，逐个尝试） */
    private static List<String> candidateSecids(String symbol) {
        List<String> list = new ArrayList<>();
        if (symbol.matches("\\d{6}")) {
            char c = symbol.charAt(0);
            if (c == '6' || c == '9' || c == '5') {
                list.add("1." + symbol);   // 沪市：主板 6、B股 9、ETF 5
            } else {
                list.add("0." + symbol);   // 深市 0/2/3，北交所 4/8
            }
            list.add("1." + symbol);
            list.add("0." + symbol);
        } else {
            list.add("105." + symbol);     // 纳斯达克
            list.add("106." + symbol);     // 纽交所
            list.add("107." + symbol);     // 美交所
        }
        return list;
    }

    /** 实时行情信息 */
    public record StockInfo(
            String symbol,
            String name,
            double price,
            double prevClose,
            double changeAmount,
            double changePercent,
            String unit,
            String time
    ) {
        @Override
        public String toString() {
            return String.format("%s(%s) 现价 %.2f %s，涨跌 %+.2f（%+.2f%%），昨收 %.2f（时间 %s）",
                    name, symbol, price, unit, changeAmount, changePercent, prevClose, time);
        }
    }
}