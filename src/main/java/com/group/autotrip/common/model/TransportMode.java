package com.group.autotrip.common.model;

/** 可推荐的交通方式。 */
public enum TransportMode {
    WALKING("步行"),
    BUS("公交"),
    METRO("地铁"),
    DRIVING("驾车"),
    RAIL("高铁/火车");

    private final String displayName;

    TransportMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
