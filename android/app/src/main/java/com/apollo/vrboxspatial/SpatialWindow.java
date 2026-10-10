package com.apollo.vrboxspatial;

import com.google.ar.core.Anchor;
import com.google.ar.core.Pose;

final class SpatialWindow {
    enum Kind { BROWSER, MEDIA, NOTES, SYSTEM }

    final String id;
    final String title;
    final Kind kind;
    Anchor anchor;

    // Snapshot of the world-space pose at the instant the user places the window.
    // ARCore Anchors can refine their pose as the map changes; using this snapshot while
    // worldLocked prevents a released panel from slowly drifting away on its own.
    Pose lockedPose;
    boolean worldLocked = true;

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

    void lockTo(Pose pose) {
        lockedPose = pose;
        worldLocked = pose != null;
    }

    void detach() {
        if (anchor != null) {
            anchor.detach();
            anchor = null;
        }
        lockedPose = null;
        worldLocked = false;
    }
}
