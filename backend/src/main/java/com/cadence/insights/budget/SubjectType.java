package com.cadence.insights.budget;

/** The grain an anomaly is evaluated at: one member, or the whole org. */
enum SubjectType {
    MEMBER("member"),
    ORG("org");

    private final String wire;

    SubjectType(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
