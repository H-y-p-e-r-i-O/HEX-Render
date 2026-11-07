package com.hex.hexrender.gpu;

import com.hex.hexrender.HexRender;
import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.render.ModelSkinningRenderer;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.util.Map;

/**
 * High-performance GPU skinning and transformation dispatcher.
 * <p>
 * Transforms geometry directly in VRAM via OpenGL 4.3 Compute Shaders and issues
 * zero-copy draw calls using Ring-Buffer streaming base-vertex offsets.
 * Seamlessly falls back to CPU skinning on legacy hardware.
 */
public final class GpuSkinningDispatcher {

    private static Boolean gpuSupported = null;
    private static boolean warnedFallback = false;

    private GpuSkinningDispatcher() {
    }

    /**
     * Checks whether the current OpenGL context supports OpenGL 4.3 Core (Compute Shaders and SSBOs).
     */
    public static boolean isSupported() {
        if (gpuSupported == null) {
            try {
                gpuSupported = GL.getCapabilities().OpenGL43;
                if (gpuSupported) {
                    HexRender.LOGGER.info("HEXRender GPU Skinning pipeline enabled (OpenGL 4.3 Core detected)");
                } else {
                    HexRender.LOGGER.warn("HEXRender GPU Skinning unsupported on this GPU; falling back to CPU LBS");
                }
            } catch (Throwable t) {
                gpuSupported = false;
            }
        }
        return gpuSupported;
    }

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
        renderModel(model, jointPalette, poseStack, bufferSource, light, overlay, tint, textureOverrides, true);
    }

    public static void renderModel(
            GltfModelAsset model,
            @Nullable Matrix4f[] jointPalette,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            @Nullable Map<ResourceLocation, ResourceLocation> textureOverrides,
            boolean allowVanillaPbr
    ) {
        if (!isSupported()) {
            ModelSkinningRenderer.renderModel(model, jointPalette, poseStack, bufferSource, light, overlay, tint, textureOverrides);
            return;
        }

        try {
            renderGpu(model, jointPalette, poseStack, bufferSource, light, overlay, tint, textureOverrides, allowVanillaPbr);
        } catch (Throwable t) {
            if (!warnedFallback) {
                warnedFallback = true;
                HexRender.LOGGER.error("GPU skinning pass encountered an error, falling back to CPU", t);
            }
            ModelSkinningRenderer.renderModel(model, jointPalette, poseStack, bufferSource, light, overlay, tint, textureOverrides);
        }
    }

    private static void renderGpu(
            GltfModelAsset model,
            @Nullable Matrix4f[] jointPalette,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            @Nullable Map<ResourceLocation, ResourceLocation> textureOverrides,
            boolean allowVanillaPbr
    ) {
        GpuMesh mesh = GpuModelManager.getOrCreate(model);
        if (mesh.totalVertices() == 0 || mesh.primitives().isEmpty()) {
            return;
        }

        PoseStack.Pose lastPose = poseStack.last();
        Matrix4f modelViewMat = lastPose.pose();
        Matrix3f normalMat = lastPose.normal();

        // Pack ARGB tint to RGBA8 uint
        int a = (tint >>> 24) & 0xFF;
        if (a == 0) a = 255;
        int r = (tint >>> 16) & 0xFF;
        int g = (tint >>> 8) & 0xFF;
        int b = tint & 0xFF;
        int packedColor = (a << 24) | (b << 16) | (g << 8) | r;

        // Obtain next streaming slot in the Ring Buffer to prevent GPU pipeline hazards across instances
        int baseVertex = mesh.nextBaseVertex();
        int slot = mesh.lastSlot();

        // 1. Dispatch GPU Compute Shader (Transforms geometry into camera space in VRAM)
        GpuSkinningShader.get().dispatch(
                mesh,
                baseVertex,
                modelViewMat,
                normalMat,
                jointPalette,
                packedColor,
                overlay,
                light
        );

        Window window = Minecraft.getInstance().getWindow();
        Matrix4f renderModelView = RenderSystem.getModelViewMatrix();
        Matrix4f renderProj = RenderSystem.getProjectionMatrix();

        // 2. Render primitives directly from Ring-Buffer VBO
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(mesh.vao());

        RenderType activeRenderType = null;
        ShaderInstance activeShader = null;

        for (GpuMesh.GpuPrimitive prim : mesh.primitives()) {
            ResourceLocation texture = prim.material().texture();
            if (textureOverrides != null && textureOverrides.containsKey(texture)) {
                texture = textureOverrides.get(texture);
            }

            RenderType renderType = ModelSkinningRenderer.resolveRenderType(texture, prim.material(), allowVanillaPbr);
            if (renderType != activeRenderType) {
                if (activeShader != null) {
                    activeShader.clear();
                }
                if (activeRenderType != null) {
                    activeRenderType.clearRenderState();
                }
                activeRenderType = renderType;
                activeRenderType.setupRenderState();
                activeShader = RenderSystem.getShader();
                if (activeShader != null) {
                    activeShader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, renderModelView, renderProj, window);
                    activeShader.apply();
                }
            }

            GL32.glDrawElementsBaseVertex(
                    GL11.GL_TRIANGLES,
                    prim.indexCount(),
                    GL11.GL_UNSIGNED_INT,
                    (long) prim.indexByteOffset(),
                    baseVertex
            );
        }

        if (activeShader != null) {
            activeShader.clear();
        }
        if (activeRenderType != null) {
            activeRenderType.clearRenderState();
        }

        GL30.glBindVertexArray(prevVao);

        // Signal that the draw calls reading this slot are in-flight
        mesh.markSlotDrawn(slot);
    }
}
