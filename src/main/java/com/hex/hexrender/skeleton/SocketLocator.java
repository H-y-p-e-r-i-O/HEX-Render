package com.hex.hexrender.skeleton;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Computes world-space attachment coordinates (e.g. muzzle flashes, shell ejectors, sights)
 * from skeleton bones and item transforms.
 */
public final class SocketLocator {

    private SocketLocator() {
    }

    /**
     * Resolves the world-space position of a socket point.
     *
     * @param modelToWorld Matrix transforming model space to world space (from PoseStack)
     * @param boneGlobal   Global matrix of the target bone in model space
     * @param socketOffset Local offset within the bone
     * @param outPosition  Destination vector to store the world-space position
     */
    public static void computeWorldPosition(
            Matrix4f modelToWorld,
            @Nullable Matrix4f boneGlobal,
            Vector3f socketOffset,
            Vector3f outPosition
    ) {
        Matrix4f combined = new Matrix4f(modelToWorld);
        if (boneGlobal != null) {
            combined.mul(boneGlobal);
        }
        Vector4f point = new Vector4f(socketOffset.x, socketOffset.y, socketOffset.z, 1.0f);
        combined.transform(point);
        if (point.w != 0.0f && point.w != 1.0f) {
            point.div(point.w);
        }
        outPosition.set(point.x, point.y, point.z);
    }

    /**
     * Resolves world position with camera offset:
     * {@code (modelToWorld * boneGlobal * socketOffset) + cameraPos}.
     */
    public static Vec3 computeWorldPositionWithCamera(
            Matrix4f modelToWorld,
            @Nullable Matrix4f boneGlobal,
            Vector3f socketOffset,
            Vec3 cameraPos
    ) {
        Matrix4f combined = new Matrix4f(modelToWorld);
        if (boneGlobal != null) {
            combined.mul(boneGlobal);
        }
        Vector4f point = new Vector4f(socketOffset.x, socketOffset.y, socketOffset.z, 1.0f);
        combined.transform(point);
        if (point.w != 0.0f && point.w != 1.0f) {
            point.div(point.w);
        }
        return new Vec3(
                point.x + cameraPos.x,
                point.y + cameraPos.y,
                point.z + cameraPos.z
        );
    }
}
