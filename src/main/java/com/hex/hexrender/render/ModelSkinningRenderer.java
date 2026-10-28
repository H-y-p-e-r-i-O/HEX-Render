package com.hex.hexrender.render;

import com.hex.hexrender.direct.HexIrisBridge;
import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.mesh.PrimitiveMaterial;
import com.hex.hexrender.mesh.SkinnedPrimitive;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * High-performance CPU Linear Blend Skinning (LBS) renderer.
 * <p>
 * Emits quad-emulated primitives into Minecraft's {@link MultiBufferSource}, enabling 100% native
 * compatibility with Iris/Oculus shaderpacks (G-buffers, shadows, SSAO, SSR) and vanilla lighting.
 */
public final class ModelSkinningRenderer {

    private static final class TransformScratch {
        float[] positions = new float[2048 * 3];
        float[] normals = new float[2048 * 3];

        void ensureCapacity(int vertexCount) {
            int needed = vertexCount * 3;
            if (positions.length < needed) {
                int newCap = Math.max(positions.length * 2, needed);
                positions = new float[newCap];
                normals = new float[newCap];
            }
        }
    }

    private static final ThreadLocal<TransformScratch> SCRATCH = ThreadLocal.withInitial(TransformScratch::new);

    private ModelSkinningRenderer() {
    }

    /**
     * Renders a glTF model using CPU skeletal skinning.
     */
    public static void renderModel(
            GltfModelAsset model,
            @Nullable Matrix4f[] jointPalette,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            @Nullable Map<ResourceLocation, ResourceLocation> textureOverrides
    ) {
        for (SkinnedPrimitive prim : model.primitives()) {
            PrimitiveMaterial mat = prim.material();
            ResourceLocation texture = mat.texture();
            if (textureOverrides != null && textureOverrides.containsKey(texture)) {
                texture = textureOverrides.get(texture);
            }

            RenderType renderType = resolveRenderType(texture, mat);
            VertexConsumer consumer = bufferSource.getBuffer(renderType);

            renderPrimitive(prim, jointPalette, poseStack, consumer, light, overlay, tint);
        }
    }

    public static RenderType resolveRenderType(@Nullable ResourceLocation texture, PrimitiveMaterial material) {
        ResourceLocation tex = texture != null ? texture : ResourceLocation.withDefaultNamespace("textures/misc/white.png");
        if (material.alphaType() == PrimitiveMaterial.AlphaType.BLEND) {
            return RenderType.entityTranslucent(tex);
        }
        if (HexIrisBridge.hasActiveShaders()) {
            return RenderType.entityCutoutNoCull(tex);
        }
        return HexRenderTypes.entityPbr(tex);
    }

    private static void renderPrimitive(
            SkinnedPrimitive prim,
            @Nullable Matrix4f[] palette,
            PoseStack poseStack,
            VertexConsumer consumer,
            int light,
            int overlay,
            int tint
    ) {
        int vertexCount = prim.vertexCount();
        if (vertexCount == 0) {
            return;
        }

        TransformScratch scratch = SCRATCH.get();
        scratch.ensureCapacity(vertexCount);
        float[] outPos = scratch.positions;
        float[] outNorm = scratch.normals;

        float[] inPos = prim.positions();
        float[] inNorm = prim.normals();
        float[] inWeights = prim.weights();
        int[] inJoints = prim.joints();

        boolean hasBones = palette != null && palette.length > 0 && inWeights != null && inJoints != null;

        if (!hasBones) {
            System.arraycopy(inPos, 0, outPos, 0, vertexCount * 3);
            System.arraycopy(inNorm, 0, outNorm, 0, vertexCount * 3);
        } else {
            for (int v = 0; v < vertexCount; v++) {
                int base3 = v * 3;
                int base4 = v * 4;

                float px = inPos[base3];
                float py = inPos[base3 + 1];
                float pz = inPos[base3 + 2];

                float nx = inNorm[base3];
                float ny = inNorm[base3 + 1];
                float nz = inNorm[base3 + 2];

                float w0 = inWeights[base4];
                float w1 = inWeights[base4 + 1];
                float w2 = inWeights[base4 + 2];
                float w3 = inWeights[base4 + 3];

                int j0 = inJoints[base4];
                int j1 = inJoints[base4 + 1];
                int j2 = inJoints[base4 + 2];
                int j3 = inJoints[base4 + 3];

                float tx = 0.0f, ty = 0.0f, tz = 0.0f;
                float tnx = 0.0f, tny = 0.0f, tnz = 0.0f;

                // Fast branch for rigid/single-bone vertices (weights >= 0.999f)
                if (w0 >= 0.999f && j0 < palette.length) {
                    Matrix4f m = palette[j0];
                    tx = m.m00() * px + m.m10() * py + m.m20() * pz + m.m30();
                    ty = m.m01() * px + m.m11() * py + m.m21() * pz + m.m31();
                    tz = m.m02() * px + m.m12() * py + m.m22() * pz + m.m32();

                    tnx = m.m00() * nx + m.m10() * ny + m.m20() * nz;
                    tny = m.m01() * nx + m.m11() * ny + m.m21() * nz;
                    tnz = m.m02() * nx + m.m12() * ny + m.m22() * nz;
                } else {
                    if (w0 > 0.0f && j0 < palette.length) {
                        Matrix4f m = palette[j0];
                        tx += w0 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w0 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w0 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w0 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w0 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w0 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                    if (w1 > 0.0f && j1 < palette.length) {
                        Matrix4f m = palette[j1];
                        tx += w1 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w1 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w1 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w1 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w1 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w1 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                    if (w2 > 0.0f && j2 < palette.length) {
                        Matrix4f m = palette[j2];
                        tx += w2 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w2 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w2 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w2 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w2 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w2 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                    if (w3 > 0.0f && j3 < palette.length) {
                        Matrix4f m = palette[j3];
                        tx += w3 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w3 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w3 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w3 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w3 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w3 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                }

                float len = (float) Math.sqrt(tnx * tnx + tny * tny + tnz * tnz);
                if (len > 1e-4f) {
                    float inv = 1.0f / len;
                    tnx *= inv;
                    tny *= inv;
                    tnz *= inv;
                } else {
                    tnx = 0.0f;
                    tny = 1.0f;
                    tnz = 0.0f;
                }

                outPos[base3] = tx;
                outPos[base3 + 1] = ty;
                outPos[base3 + 2] = tz;

                outNorm[base3] = tnx;
                outNorm[base3 + 1] = tny;
                outNorm[base3 + 2] = tnz;
            }
        }

        int a = (tint >>> 24) & 0xFF;
        if (a == 0) a = 255;
        int r = (tint >>> 16) & 0xFF;
        int g = (tint >>> 8) & 0xFF;
        int b = tint & 0xFF;

        PoseStack.Pose poseEntry = poseStack.last();
        int[] indices = prim.indices();
        float[] uvs = prim.uvs();
        int indexCount = prim.indexCount();

        // Quad emulation: for each triangle (i0, i1, i2), emit (i0, i1, i2, i2)
        for (int i = 0; i < indexCount; i += 3) {
            int i0 = indices[i];
            int i1 = indices[i + 1];
            int i2 = indices[i + 2];

            writeVertex(consumer, poseEntry, i0, outPos, outNorm, uvs, r, g, b, a, overlay, light);
            writeVertex(consumer, poseEntry, i1, outPos, outNorm, uvs, r, g, b, a, overlay, light);
            writeVertex(consumer, poseEntry, i2, outPos, outNorm, uvs, r, g, b, a, overlay, light);
            writeVertex(consumer, poseEntry, i2, outPos, outNorm, uvs, r, g, b, a, overlay, light);
        }
    }

    private static void writeVertex(
            VertexConsumer consumer,
            PoseStack.Pose poseEntry,
            int index,
            float[] positions,
            float[] normals,
            float[] uvs,
            int r, int g, int b, int a,
            int overlay,
            int light
    ) {
        int base3 = index * 3;
        int base2 = index * 2;

        consumer.addVertex(poseEntry, positions[base3], positions[base3 + 1], positions[base3 + 2])
                .setColor(r, g, b, a)
                .setUv(uvs[base2], uvs[base2 + 1])
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(poseEntry, normals[base3], normals[base3 + 1], normals[base3 + 2]);
    }
}
