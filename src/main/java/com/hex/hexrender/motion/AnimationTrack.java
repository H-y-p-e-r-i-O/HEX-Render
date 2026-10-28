package com.hex.hexrender.motion;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Single animation channel track animating translation, rotation, or scale of a target node.
 */
public class AnimationTrack {
    public enum ChannelType {
        TRANSLATION,
        ROTATION,
        SCALE
    }

    public enum Interpolation {
        STEP,
        LINEAR,
        CUBICSPLINE
    }

    private final int nodeIndex;
    private final ChannelType channel;
    private final Interpolation interpolation;
    private final float[] timestamps;
    private final float[] values;

    public AnimationTrack(
            int nodeIndex,
            ChannelType channel,
            Interpolation interpolation,
            float[] timestamps,
            float[] values
    ) {
        this.nodeIndex = nodeIndex;
        this.channel = channel;
        this.interpolation = interpolation;
        this.timestamps = timestamps;
        this.values = values;
    }

    public int nodeIndex() {
        return nodeIndex;
    }

    public ChannelType channel() {
        return channel;
    }

    public void sample(float time, Vector3f outVec, Quaternionf outQuat) {
        if (timestamps.length == 0) {
            return;
        }

        if (time <= timestamps[0]) {
            assignKeyframe(0, outVec, outQuat);
            return;
        }

        int lastIdx = timestamps.length - 1;
        if (time >= timestamps[lastIdx]) {
            assignKeyframe(lastIdx, outVec, outQuat);
            return;
        }

        // Binary search for keyframe segment
        int low = 0;
        int high = lastIdx;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (timestamps[mid] < time) {
                low = mid + 1;
            } else if (timestamps[mid] > time) {
                high = mid - 1;
            } else {
                assignKeyframe(mid, outVec, outQuat);
                return;
            }
        }

        int prev = Math.max(0, low - 1);
        int next = Math.min(lastIdx, low);

        if (interpolation == Interpolation.STEP || prev == next) {
            assignKeyframe(prev, outVec, outQuat);
            return;
        }

        float t0 = timestamps[prev];
        float t1 = timestamps[next];
        float factor = (time - t0) / (t1 - t0);

        if (channel == ChannelType.ROTATION) {
            int off0 = prev * 4;
            int off1 = next * 4;
            float q0x = values[off0], q0y = values[off0 + 1], q0z = values[off0 + 2], q0w = values[off0 + 3];
            float q1x = values[off1], q1y = values[off1 + 1], q1z = values[off1 + 2], q1w = values[off1 + 3];

            // Shortest path interpolation (flip if dot < 0)
            float dot = q0x * q1x + q0y * q1y + q0z * q1z + q0w * q1w;
            if (dot < 0.0f) {
                q1x = -q1x;
                q1y = -q1y;
                q1z = -q1z;
                q1w = -q1w;
            }

            outQuat.set(
                    q0x + factor * (q1x - q0x),
                    q0y + factor * (q1y - q0y),
                    q0z + factor * (q1z - q0z),
                    q0w + factor * (q1w - q0w)
            ).normalize();
        } else {
            int off0 = prev * 3;
            int off1 = next * 3;
            outVec.set(
                    values[off0] + factor * (values[off1] - values[off0]),
                    values[off0 + 1] + factor * (values[off1 + 1] - values[off0 + 1]),
                    values[off0 + 2] + factor * (values[off1 + 2] - values[off0 + 2])
            );
        }
    }

    private void assignKeyframe(int index, Vector3f outVec, Quaternionf outQuat) {
        if (channel == ChannelType.ROTATION) {
            int off = index * 4;
            outQuat.set(values[off], values[off + 1], values[off + 2], values[off + 3]);
        } else {
            int off = index * 3;
            outVec.set(values[off], values[off + 1], values[off + 2]);
        }
    }
}
