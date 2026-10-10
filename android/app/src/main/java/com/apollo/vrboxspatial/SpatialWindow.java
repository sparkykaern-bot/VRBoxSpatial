package com.apollo.vrboxspatial;

import com.google.ar.core.Anchor;

final class SpatialWindow {
    enum Kind { BROWSER, MEDIA, NOTES, SYSTEM }

    final String id;
    final String title;
    final Kind kind;
    Anchor anchor;
    float widthMeters;
    float heightMeters;
    boolean visible = true;
    float depthMeters;

    SpatialWindow(String id, String title, Kind kind, float widthMeters, float heightMeters, float depthMeters) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.widthMeters = widthMeters;
        this.heightMeters = heightMeters;
        this.depthMeters = depthMeters;
    }

    void detach() {
        if (anchor != null) {
            anchor.detach();
            anchor = null;
        }
    }
}
