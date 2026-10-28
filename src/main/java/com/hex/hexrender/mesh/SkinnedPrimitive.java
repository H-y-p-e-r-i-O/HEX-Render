package com.hex.hexrender.mesh;

import org.jetbrains.annotations.Nullable;

/**
 * Single textured, skinned geometry primitive containing vertices, normals, UVs and bone influences.
 */
public record SkinnedPrimitive(
        float[] positions,
        float[] normals,
        float[] uvs,
        @Nullable float[] weights,
        @Nullable int[] joints,
        int[] indices,
        int vertexCount,
        int indexCount,
        PrimitiveMaterial material
) {
    public boolean hasSkeleton() {
        return joints != null && weights != null && joints.length > 0;
    }
}
