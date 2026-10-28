package com.hex.hexrender.skeleton;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Skeleton node hierarchy, rest transforms, and inverse bind matrices for skeletal animation.
 */
public class RigHierarchy {
    private final String[] nodeNames;
    private final int[] parentIndices;
    private final Vector3f[] restTranslations;
    private final Quaternionf[] restRotations;
    private final Vector3f[] restScales;

    private final int[] jointNodeIndices;
    private final Matrix4f[] inverseBindMatrices;
    private final int[] evaluationOrder;
    private final Map<String, Integer> nameToNode = new HashMap<>();

    public RigHierarchy(
            String[] nodeNames,
            int[] parentIndices,
            Vector3f[] restTranslations,
            Quaternionf[] restRotations,
            Vector3f[] restScales,
            int[] jointNodeIndices,
            Matrix4f[] inverseBindMatrices
    ) {
        this.nodeNames = nodeNames;
        this.parentIndices = parentIndices;
        this.restTranslations = restTranslations;
        this.restRotations = restRotations;
        this.restScales = restScales;
        this.jointNodeIndices = jointNodeIndices;
        this.inverseBindMatrices = inverseBindMatrices;

        for (int i = 0; i < nodeNames.length; i++) {
            if (nodeNames[i] != null) {
                nameToNode.put(nodeNames[i], i);
            }
        }

        // Compute topological evaluation order (parents before children)
        int count = nodeNames.length;
        int[] depth = new int[count];
        for (int i = 0; i < count; i++) {
            int cur = i;
            int d = 0;
            while (cur >= 0 && d < count) {
                cur = parentIndices[cur];
                if (cur >= 0) d++;
            }
            depth[i] = d;
        }
        Integer[] sorted = new Integer[count];
        for (int i = 0; i < count; i++) sorted[i] = i;
        java.util.Arrays.sort(sorted, java.util.Comparator.comparingInt(a -> depth[a]));
        this.evaluationOrder = new int[count];
        for (int i = 0; i < count; i++) {
            this.evaluationOrder[i] = sorted[i];
        }
    }

    public int nodeCount() {
        return nodeNames.length;
    }

    public int jointCount() {
        return jointNodeIndices.length;
    }

    public int findNodeIndex(String name) {
        Integer index = nameToNode.get(name);
        return index != null ? index : -1;
    }

    @Nullable
    public String nodeName(int nodeIndex) {
        return (nodeIndex >= 0 && nodeIndex < nodeNames.length) ? nodeNames[nodeIndex] : null;
    }

    public int parentIndex(int nodeIndex) {
        return parentIndices[nodeIndex];
    }

    public int evaluationOrder(int step) {
        return evaluationOrder[step];
    }

    public int[] evaluationOrder() {
        return evaluationOrder;
    }

    public Vector3f restTranslation(int nodeIndex) {
        return restTranslations[nodeIndex];
    }

    public Quaternionf restRotation(int nodeIndex) {
        return restRotations[nodeIndex];
    }

    public Vector3f restScale(int nodeIndex) {
        return restScales[nodeIndex];
    }

    public int jointNode(int jointIndex) {
        return jointNodeIndices[jointIndex];
    }

    public Matrix4f inverseBindMatrix(int jointIndex) {
        return inverseBindMatrices[jointIndex];
    }

    public Matrix4f[] inverseBindMatrices() {
        return inverseBindMatrices;
    }
}
