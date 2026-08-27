package com.group.autotrip.agent;

import com.group.autotrip.common.model.Itinerary;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户保存最新行程单（供预算监控与重规划使用），变更时持久化到本地状态文件。
 */
@Component
public class TripPlanStore {

    private final StateStore stateStore;
    private final Map<String, Itinerary> itineraries = new ConcurrentHashMap<>();

    public TripPlanStore(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    /** 启动时从状态文件恢复行程单 */
    @PostConstruct
    void restore() {
        if (stateStore == null) {
            return;
        }
        Map<String, Itinerary> saved = stateStore.load().itineraries();
        if (saved != null) {
            itineraries.putAll(saved);
        }
    }

    /** 保存（覆盖）某用户的最新行程单 */
    public void save(String userId, Itinerary itinerary) {
        itineraries.put(keyOf(userId), itinerary);
        persist();
    }

    public Itinerary get(String userId) {
        return itineraries.get(keyOf(userId));
    }

    public boolean has(String userId) {
        return itineraries.containsKey(keyOf(userId));
    }

    /** 全部用户行程单（供持久化） */
    public Map<String, Itinerary> all() {
        return Map.copyOf(itineraries);
    }

    private void persist() {
        if (stateStore != null) {
            stateStore.saveItineraries(all());
        }
    }

    private static String keyOf(String userId) {
        return userId == null ? "" : userId;
    }
}
