package com.hex.hexrender.skeleton;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Forward kinematics solver that transforms skeleton node hierarchy into joint skinning matrices.
 */
public final class SkeletonPose {

    public static final class NodeTransformState {
        public final Vector3f[] translations;
        public final Quaternionf[] rotations;
        public final Vector3f[] scales;

        public NodeTransformState(int capacity) {
            this.translations = new Vector3f[capacity];
            this.rotations = new Quaternionf[capacity];
            this.scales = new Vector3f[capacity];
            for (int i = 0; i < capacity; i++) {
                translations[i] = new Vector3f();
                rotations[i] = new Quaternionf();
                scales[i] = new Vector3f(1.0f, 1.0f, 1.0f);
            }
        }

        public void reset(RigHierarchy rig) {
            int count = rig.nodeCount();
            for (int i = 0; i < count; i++) {
                translations[i].set(rig.restTranslation(i));
                rotations[i].set(rig.restRotation(i));
                scales[i].set(rig.restScale(i));
            }
        }
    }

    public static final class PoseScratch {
        private Matrix4f[] globalTransforms = new Matrix4f[0];
        private final Matrix4f localMat = new Matrix4f();

        public Matrix4f[] ensureGlobalCapacity(int size) {
            if (globalTransforms.length < size) {
                int old = globalTransforms.length;
                globalTransforms = Arrays.copyOf(globalTransforms, size);
                for (int i = old; i < size; i++) {
                    globalTransforms[i] = new Matrix4f();
                }
            }
            return globalTransforms;
        }
    }

    private static final ThreadLocal<PoseScratch> SCRATCH = ThreadLocal.withInitial(PoseScratch::new);

    private SkeletonPose() {
    }

    /**
     * Solves forward kinematics and produces final skinning matrices:
     * {@code jointPalette[j] = globalMatrix[jointNode[j]] * inverseBindMatrix[j]}.
     */
    public static void computeJointPalette(
            RigHierarchy rig,
            NodeTransformState state,
            Matrix4f[] outPalette
    ) {
        int nodeCount = rig.nodeCount();
        int jointCount = rig.jointCount();
        if (nodeCount == 0 || jointCount == 0) {
            return;
        }

        PoseScratch scratch = SCRATCH.get();
        Matrix4f[] globals = scratch.ensureGlobalCapacity(nodeCount);
        Matrix4f local = scratch.localMat;

        for (int step = 0; step < nodeCount; step++) {
            int i = rig.evaluationOrder(step);
            local.translationRotateScale(state.translations[i], state.rotations[i], state.scales[i]);
            int parent = rig.parentIndex(i);
            if (parent < 0) {
                globals[i].set(local);
            } else {
                globals[parent].mul(local, globals[i]);
            }
        }

        for (int j = 0; j < jointCount; j++) {
            int node = rig.jointNode(j);
            globals[node].mul(rig.inverseBindMatrix(j), outPalette[j]);
        }
    }

    /**
     * Solves forward kinematics and returns the global model-space transform of a specific node.
     */
    public static void computeNodeGlobal(
            RigHierarchy rig,
            NodeTransformState state,
            int targetNode,
            Matrix4f outMatrix
    ) {
        int nodeCount = rig.nodeCount();
        if (nodeCount == 0 || targetNode < 0 || targetNode >= nodeCount) {
            outMatrix.identity();
            return;
        }

        PoseScratch scratch = SCRATCH.get();
        Matrix4f[] globals = scratch.ensureGlobalCapacity(nodeCount);
        Matrix4f local = scratch.localMat;

        for (int step = 0; step < nodeCount; step++) {
            int i = rig.evaluationOrder(step);
            local.translationRotateScale(state.translations[i], state.rotations[i], state.scales[i]);
            int parent = rig.parentIndex(i);
            if (parent < 0) {
                globals[i].set(local);
            } else {
                globals[parent].mul(local, globals[i]);
            }
        }

        outMatrix.set(globals[targetNode]);
    }

    /**
     * Computes the rest pose palette.
     */
    public static void computeRestPalette(RigHierarchy rig, Matrix4f[] outPalette) {
        NodeTransformState state = new NodeTransformState(rig.nodeCount());
        state.reset(rig);
        computeJointPalette(rig, state, outPalette);
    }
}
