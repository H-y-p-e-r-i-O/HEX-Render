package com.hex.hexrender.loader;

import com.hex.hexrender.mesh.SkinnedPrimitive;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.hex.hexrender.skeleton.RigHierarchy;
import com.hex.hexrender.skeleton.SkeletonPose;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable in-memory container for a fully imported glTF 2.0 asset.
 */
public class GltfModelAsset {
    private final ResourceLocation id;
    private final List<SkinnedPrimitive> primitives;
    private final RigHierarchy skeleton;
    private final Map<String, SkeletalAnimation> animations;
    private final Map<String, Vector3f> sockets;

    public GltfModelAsset(
            ResourceLocation id,
            List<SkinnedPrimitive> primitives,
            RigHierarchy skeleton,
            Map<String, SkeletalAnimation> animations,
            Map<String, Vector3f> sockets
    ) {
        this.id = id;
        this.primitives = List.copyOf(primitives);
        this.skeleton = skeleton;
        this.animations = Map.copyOf(animations);
        this.sockets = Map.copyOf(sockets);
    }

    public ResourceLocation id() {
        return id;
    }

    public List<SkinnedPrimitive> primitives() {
        return primitives;
    }

    public RigHierarchy skeleton() {
        return skeleton;
    }

    public int jointCount() {
        return skeleton.jointCount();
    }

    public Map<String, SkeletalAnimation> animations() {
        return animations;
    }

    @Nullable
    public SkeletalAnimation animation(String name) {
        return animations.get(name);
    }

    @Nullable
    public Vector3f socket(String name) {
        return sockets.get(name);
    }

    public Map<String, Vector3f> sockets() {
        return sockets;
    }

    /**
     * Allocates an array of identity matrices sized to this model's joint count.
     */
    public Matrix4f[] newPalette() {
        int count = skeleton.jointCount();
        Matrix4f[] palette = new Matrix4f[count];
        for (int i = 0; i < count; i++) {
            palette[i] = new Matrix4f();
        }
        return palette;
    }

    /**
     * Evaluates animation clip at the given timestamp and outputs skinning joint matrices.
     */
    public void evaluatePose(@Nullable SkeletalAnimation clip, float seconds, Matrix4f[] outPalette) {
        evaluatePose(clip, seconds, outPalette, (String[]) null);
    }

    /**
     * Evaluates animation clip at the given timestamp and outputs skinning joint matrices,
     * collapsing the specified nodes to scale zero (hiding them from view).
     */
    public void evaluatePose(
            @Nullable SkeletalAnimation clip,
            float seconds,
            Matrix4f[] outPalette,
            @Nullable String... hiddenNodes
    ) {
        if (skeleton.jointCount() == 0) {
            return;
        }
        SkeletonPose.NodeTransformState state = new SkeletonPose.NodeTransformState(skeleton.nodeCount());
        state.reset(skeleton);
        if (clip != null) {
            clip.apply(seconds, state);
        }
        if (hiddenNodes != null) {
            for (String name : hiddenNodes) {
                if (name == null) continue;
                int node = skeleton.findNodeIndex(name);
                if (node >= 0) {
                    state.scales[node].set(0.0f, 0.0f, 0.0f);
                }
            }
        }
        SkeletonPose.computeJointPalette(skeleton, state, outPalette);
    }

    /**
     * Computes the global model-space transform of a specific node under the given animation clip and timestamp.
     */
    public boolean computeNodeGlobal(
            int nodeIndex,
            @Nullable SkeletalAnimation clip,
            float seconds,
            Matrix4f outMatrix
    ) {
        if (nodeIndex < 0 || nodeIndex >= skeleton.nodeCount()) {
            outMatrix.identity();
            return false;
        }
        SkeletonPose.NodeTransformState state = new SkeletonPose.NodeTransformState(skeleton.nodeCount());
        state.reset(skeleton);
        if (clip != null) {
            clip.apply(seconds, state);
        }
        SkeletonPose.computeNodeGlobal(skeleton, state, nodeIndex, outMatrix);
        return true;
    }
}
