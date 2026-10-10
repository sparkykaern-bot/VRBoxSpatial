package com.apollo.vrboxspatial;

import com.google.ar.core.Anchor;
import com.google.ar.core.Pose;

final class SpatialWindow {
    enum Kind { BROWSER, MEDIA, NOTES, SYSTEM }

    final String id;
    final String title;
    final Kind kind;
    Anchor anchor;

    // Snapshot of the world-space pose at the instant this specific Anchor becomes active.
    // ARCore may keep refining Anchor.getPose(); the UI deliberately ignores those slow
    // corrections after the snapshot so a released panel cannot creep away by itself.
    Pose lockedPose;
    Anchor lockedFromAnchor;
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

    void lockToCurrentAnchor() {
        if (anchor != null) {
            lockedPose = anchor.getPose();
            lockedFromAnchor = anchor;
            worldLocked = true;
        }
    }

    void detach() {
        if (anchor != null) {
            anchor.detach();
            anchor = null;
        }
        lockedPose = null;
        lockedFromAnchor = null;
        worldLocked = false;
    }
}
