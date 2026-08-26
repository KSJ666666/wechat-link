package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTrafficToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void returnsRoadTraffic() throws Exception {
        QueryTrafficTool tool = new QueryTrafficTool(new FakeAmapService(), new CityTransportSupport());
        ObjectNode args = MAPPER.createObjectNode();
        args.put("city", "北京");
        args.put("road", "北四环中路");

        String result = tool.execute(args);

        assertTrue(result.contains("北四环中路"));
        assertTrue(result.contains("畅通"));
    }

    private static final class FakeAmapService extends AmapService {
        @Override
        public TrafficInfo getRoadTraffic(String cityOrAdcode, String road, String level) {
            assertEquals("110000", cityOrAdcode);
            assertEquals("北四环中路", road);
            assertEquals("5", level);
            return new TrafficInfo(
                    "畅通",
                    "",
                    List.of(new RoadTraffic("北四环中路", "畅通", "东向西", "60", "")));
        }
    }
}
