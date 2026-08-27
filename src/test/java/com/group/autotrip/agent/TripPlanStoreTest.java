package com.group.autotrip.agent;

import com.group.autotrip.common.model.Itinerary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripPlanStoreTest {

    private static Itinerary itinerary(String title) {
        return new Itinerary(title, null, java.util.List.of(), 0, java.util.List.of(), null, null);
    }

    @Test
    void saveAndGetPerUser() {
        TripPlanStore store = new TripPlanStore(null);
        store.save("u1", itinerary("杭州行程"));
        store.save("u2", itinerary("大理行程"));
        assertEquals("杭州行程", store.get("u1").title());
        assertEquals("大理行程", store.get("u2").title());
        assertTrue(store.has("u1"));
        assertFalse(store.has("u3"));
    }

    @Test
    void saveOverwritesPrevious() {
        TripPlanStore store = new TripPlanStore(null);
        store.save("u1", itinerary("第一版"));
        store.save("u1", itinerary("第二版"));
        assertEquals("第二版", store.get("u1").title());
    }

    @Test
    void nullUserIdSharesBucket() {
        TripPlanStore store = new TripPlanStore(null);
        store.save(null, itinerary("匿名行程"));
        assertEquals("匿名行程", store.get(null).title());
        assertEquals("匿名行程", store.get("").title());
    }
}
