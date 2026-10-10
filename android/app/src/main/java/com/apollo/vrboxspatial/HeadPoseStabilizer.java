package com.apollo.vrboxspatial;

import com.google.ar.core.Pose;

/**
 * Rejects the tiny continuous pose corrections ARCore can make while the phone is physically
 * still. Those corrections are useful for mapping, but on a cheap phone-in-headset setup they can
 * make a floating panel appear to creep away on its own.
 */
final class HeadPoseStabilizer {
    private Pose stable = Pose.IDENTITY;
    private Pose previousRaw = Pose.IDENTITY;
    private boolean ready;
    private boolean moving;
    private int movingFrames;
    private int stillFrames;

    Pose update(Pose raw, boolean tracking) {
        if (!tracking || raw == null) return stable;
        if (!ready) {
            stable = raw;
            previousRaw = raw;
            ready = true;
            moving = false;
            return stable;
        }

        float stepM = translationDistance(previousRaw, raw);
        float stepDeg = rotationDeltaDeg(previousRaw, raw);
        float totalM = translationDistance(stable, raw);
        float totalDeg = rotationDeltaDeg(stable, raw);
        previousRaw = raw;

        // Frame-to-frame motion catches actual head movement. The cumulative thresholds make sure
        // an intentionally very slow turn eventually unlocks too, while sub-millimetre map drift
        // remains frozen indefinitely.
        boolean intentional = stepM > .00085f || stepDeg > .060f ||
                totalM > .012f || totalDeg > .85f;

        if (intentional) {
            movingFrames++;
            stillFrames = 0;
        } else {
            stillFrames++;
            movingFrames = 0;
        }

        if (!moving && movingFrames >= 2) moving = true;
        if (moving && stillFrames >= 6) moving = false;

        if (moving) stable = blend(stable, raw, .82f);
        return stable;
    }

    void reset() {
        stable = Pose.IDENTITY;
        previousRaw = Pose.IDENTITY;
        ready = false;
        moving = false;
        movingFrames = 0;
        stillFrames = 0;
    }

    boolean isMoving() { return moving; }

    private static Pose blend(Pose a, Pose b, float t) {
        float[] at = a.getTranslation();
        float[] bt = b.getTranslation();
        float[] tr = new float[]{
                lerp(at[0], bt[0], t),
                lerp(at[1], bt[1], t),
                lerp(at[2], bt[2], t)
        };

        float[] qa = a.getRotationQuaternion();
        float[] qb = b.getRotationQuaternion();
        float dot = qa[0]*qb[0] + qa[1]*qb[1] + qa[2]*qb[2] + qa[3]*qb[3];
        if (dot < 0f) {
            qb = new float[]{-qb[0],-qb[1],-qb[2],-qb[3]};
        }
        float[] q = new float[]{
                lerp(qa[0], qb[0], t),
                lerp(qa[1], qb[1], t),
                lerp(qa[2], qb[2], t),
                lerp(qa[3], qb[3], t)
        };
        float n = (float)Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
        if (n > 0.00001f) {
            q[0]/=n; q[1]/=n; q[2]/=n; q[3]/=n;
        }
        return new Pose(tr, q);
    }

    private static float translationDistance(Pose a, Pose b) {
        float[] x = a.getTranslation();
        float[] y = b.getTranslation();
        float dx=x[0]-y[0], dy=x[1]-y[1], dz=x[2]-y[2];
        return (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    private static float rotationDeltaDeg(Pose a, Pose b) {
        float[] x = a.getRotationQuaternion();
        float[] y = b.getRotationQuaternion();
        float dot = Math.abs(x[0]*y[0]+x[1]*y[1]+x[2]*y[2]+x[3]*y[3]);
        dot = Math.max(-1f, Math.min(1f, dot));
        return (float)Math.toDegrees(2.0*Math.acos(dot));
    }

    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
}
