package com.hex.hexrender.motion;

import com.hex.hexrender.skeleton.SkeletonPose;

import java.util.List;

/**
 * Skeletal animation clip made of node channels.
 */
public class SkeletalAnimation {
    private final String name;
    private final float duration;
    private final List<AnimationTrack> tracks;

    public SkeletalAnimation(String name, float duration, List<AnimationTrack> tracks) {
        this.name = name;
        this.duration = Math.max(0.0f, duration);
        this.tracks = List.copyOf(tracks);
    }

    public String name() {
        return name;
    }

    public float duration() {
        return duration;
    }

    public List<AnimationTrack> tracks() {
        return tracks;
    }

    /**
     * Loops a continuous timestamp into the clip's duration.
     */
    public float loopTime(float timeSeconds) {
        if (duration <= 0.0f) {
            return 0.0f;
        }
        float mod = timeSeconds % duration;
        return mod >= 0.0f ? mod : mod + duration;
    }

    /**
     * Samples this animation at the specified time and writes the results into the node transform state.
     */
    public void apply(float timeSeconds, SkeletonPose.NodeTransformState state) {
        float sampleTime = loopTime(timeSeconds);
        for (AnimationTrack track : tracks) {
            int node = track.nodeIndex();
            if (node < 0 || node >= state.translations.length) {
                continue;
            }
            if (track.channel() == AnimationTrack.ChannelType.ROTATION) {
                track.sample(sampleTime, state.translations[node], state.rotations[node]);
            } else if (track.channel() == AnimationTrack.ChannelType.TRANSLATION) {
                track.sample(sampleTime, state.translations[node], state.rotations[node]);
            } else if (track.channel() == AnimationTrack.ChannelType.SCALE) {
                track.sample(sampleTime, state.scales[node], state.rotations[node]);
            }
        }
    }
}
