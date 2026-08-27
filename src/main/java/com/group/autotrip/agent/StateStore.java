package com.group.autotrip.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.group.autotrip.common.model.Itinerary;
import com.group.autotrip.common.model.MonitorTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 本地状态持久化：监控列表 + 行程单，JSON 文件存储；读写失败只告警不影响运行。
 *
 * <p>两个数据源各自独立写入，但都通过本类的同步方法做"读-改-写"，避免互相覆盖。
 */
@Component
public class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final File file;

    public StateStore(@Value("${app.state-file:${user.home}/.autotrip-state.json}") String path) {
        this.file = Path.of(path).toFile();
    }

    /** 持久化状态：监控列表 + 行程单 */
    public record State(Map<String, List<MonitorTarget>> monitors, Map<String, Itinerary> itineraries) {
    }

    /** 读取状态；文件不存在或解析失败时返回空状态 */
    public synchronized State load() {
        if (file == null || !file.isFile()) {
            return new State(Map.of(), Map.of());
        }
        try {
            State state = mapper.readValue(file, State.class);
            return new State(
                    state.monitors() == null ? Map.of() : state.monitors(),
                    state.itineraries() == null ? Map.of() : state.itineraries());
        } catch (Exception e) {
            log.warn("读取状态文件失败（{}），按空状态处理：{}", file, e.getMessage());
            return new State(Map.of(), Map.of());
        }
    }

    /** 保存监控列表（保留已有行程单部分） */
    public synchronized void saveMonitors(Map<String, List<MonitorTarget>> monitors) {
        write(new State(Map.copyOf(monitors), load().itineraries()));
    }

    /** 保存行程单（保留已有监控列表部分） */
    public synchronized void saveItineraries(Map<String, Itinerary> itineraries) {
        write(new State(load().monitors(), Map.copyOf(itineraries)));
    }

    private void write(State state) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("无法创建状态目录：{}", parent);
            }
            mapper.writeValue(file, state);
        } catch (IOException e) {
            log.warn("保存状态文件失败：{}", e.getMessage());
        }
    }
}
