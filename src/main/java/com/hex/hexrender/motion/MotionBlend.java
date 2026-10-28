package com.hex.hexrender.motion;

import com.hex.hexrender.skeleton.RigHierarchy;
import com.hex.hexrender.skeleton.SkeletonPose;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Blends skeletal animations with crossfading and layer weighting.
 */
public final class MotionBlend {

    private static final class BlendScratch {
        SkeletonPose.NodeTransformState tempState = new SkeletonPose.NodeTransformState(64);

        SkeletonPose.NodeTransformState ensure(int capacity) {
            if (tempState.translations.length < capacity) {
                tempState = new SkeletonPose.NodeTransformState(capacity);
            }
            return tempState;
        }
    }

    private static final ThreadLocal<BlendScratch> SCRATCH = ThreadLocal.withInitial(BlendScratch::new);

    private MotionBlend() {
    }

    /**
     * Blends two animations into the output state according to blend weight:
     * {@code out = (1 - weight) * clipA + weight * clipB}.
     */
    public static void blend(
            RigHierarchy rig,
            @Nullable SkeletalAnimation clipA,
            float timeA,
            @Nullable SkeletalAnimation clipB,
            float timeB,
            float weightB,
            SkeletonPose.NodeTransformState outState
    ) {
        float alpha = Math.max(0.0f, Math.min(1.0f, weightB));

        if (alpha <= 0.001f || clipB == null) {
            outState.reset(rig);
            if (clipA != null) {
                clipA.apply(timeA, outState);
            }
            return;
        }

        if (alpha >= 0.999f || clipA == null) {
            outState.reset(rig);
            clipB.apply(timeB, outState);
            return;
        }

        // Evaluate clipA
        outState.reset(rig);
        clipA.apply(timeA, outState);

        // Evaluate clipB into scratch
        int count = rig.nodeCount();
        BlendScratch scratch = SCRATCH.get();
        SkeletonPose.NodeTransformState stateB = scratch.ensure(count);
        stateB.reset(rig);
        clipB.apply(timeB, stateB);

        // Blend TRS
        for (int i = 0; i < count; i++) {
            Vector3f ta = outState.translations[i];
            Vector3f tb = stateB.translations[i];
            ta.lerp(tb, alpha);

            Quaternionf qa = outState.rotations[i];
            Quaternionf qb = stateB.rotations[i];
            qa.slerp(qb, alpha).normalize();

            Vector3f sa = outState.scales[i];
            Vector3f sb = stateB.scales[i];
            sa.lerp(sb, alpha);
        }
    }
}
