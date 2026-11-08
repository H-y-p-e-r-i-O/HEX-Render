package com.hex.hexrender.gpu;

import com.hex.hexrender.direct.HexIrisBridge;
import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.render.ModelSkinningRenderer;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/**
 * High-performance GPU Compute Multi-Instance Batch Collector and Dispatcher.
 * <p>
 * Batches identical multiblock and block-entity models across the frame, transforming all instances
 * in a single Compute Shader dispatch and rendering all instances in a single {@code glMultiDrawElementsBaseVertex}
 * call per primitive material.
 * <p>
 * Fully compatible with Iris shaderpacks (GBuffers, Shadows, PBR) and vanilla lighting.
 */
public final class InstanceBatchCollector {

    public static volatile boolean ENABLE_INSTANCING = true;
    public static final int MAX_BATCH_INSTANCES = 128;
    public static final int INSTANCE_STRIDE = 144; // 64 (mat4) + 64 (mat4) + 4 + 4 + 4 + 4

    private static final record BatchKey(
            GltfModelAsset model,
            boolean allowVanillaPbr,
            @Nullable Map<ResourceLocation, ResourceLocation> overrides
    ) {}

    private static final class InstanceRecord {
        final Matrix4f modelView = new Matrix4f();
        final Matrix4f normalMat4 = new Matrix4f();
        int color;
        int overlay;
        int light;
    }

    private static final Map<BatchKey, List<InstanceRecord>> ACTIVE_BATCHES = new LinkedHashMap<>();
    private static final List<InstanceRecord> RECORD_POOL = new ArrayList<>(256);
    private static int poolIndex = 0;

    private static final ByteBuffer INSTANCE_DATA_BUF = BufferUtils.createByteBuffer(MAX_BATCH_INSTANCES * INSTANCE_STRIDE).order(ByteOrder.nativeOrder());
    private static final FloatBuffer FLOAT_BUF_16 = BufferUtils.createFloatBuffer(16);

    private static boolean lastWasShadow = false;

    private InstanceBatchCollector() {}

    private static InstanceRecord obtainRecord() {
        if (poolIndex < RECORD_POOL.size()) {
            return RECORD_POOL.get(poolIndex++);
        }
        InstanceRecord rec = new InstanceRecord();
        RECORD_POOL.add(rec);
        poolIndex++;
        return rec;
    }

    /**
     * Submits an instance of a model for batched GPU compute skinning and multi-draw rendering.
     */
    public static void submit(
            GltfModelAsset model,
            Matrix4f modelViewMat,
            Matrix3f normalMat,
            int color,
            int overlay,
            int light,
            @Nullable Map<ResourceLocation, ResourceLocation> textureOverrides,
            boolean allowVanillaPbr
    ) {
        if (HexIrisBridge.isShadowPassActive()) {
            return;
        }

        BatchKey key = new BatchKey(model, allowVanillaPbr, textureOverrides);
        List<InstanceRecord> list = ACTIVE_BATCHES.computeIfAbsent(key, k -> new ArrayList<>(32));

        if (list.size() >= MAX_BATCH_INSTANCES) {
            flushBatch(key, list);
            list.clear();
        }

        InstanceRecord rec = obtainRecord();
        rec.modelView.set(modelViewMat);

        // Convert 3x3 normal matrix into 4x4 matrix for std430 alignment
        rec.normalMat4.identity();
        rec.normalMat4.set3x3(normalMat);

        rec.color = color;
        rec.overlay = overlay;
        rec.light = light;

        list.add(rec);
    }

    /**
     * NeoForge render stage event handler to automatically flush batched instances.
     */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES ||
            event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            flushAll();
        }
    }

    /**
     * Flushes all pending batches across all models.
     */
    public static void flushAll() {
        if (ACTIVE_BATCHES.isEmpty()) {
            return;
        }

        for (Map.Entry<BatchKey, List<InstanceRecord>> entry : ACTIVE_BATCHES.entrySet()) {
            List<InstanceRecord> list = entry.getValue();
            if (!list.isEmpty()) {
                flushBatch(entry.getKey(), list);
                list.clear();
            }
        }
        ACTIVE_BATCHES.clear();
        poolIndex = 0;
    }

    private static void flushBatch(BatchKey key, List<InstanceRecord> instances) {
        int count = instances.size();
        if (count == 0) return;

        GpuMesh mesh = GpuModelManager.getOrCreate(key.model());
        if (mesh.totalVertices() == 0 || mesh.primitives().isEmpty()) {
            return;
        }

        // 1. Allocate Ring Buffer slots for each instance
        int[] baseVertices = new int[count];
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            baseVertices[i] = mesh.nextBaseVertex();
            slots[i] = mesh.lastSlot();
        }

        // 2. Pack InstanceData into SSBO buffer
        INSTANCE_DATA_BUF.clear();
        for (int i = 0; i < count; i++) {
            InstanceRecord inst = instances.get(i);

            // mat4 modelView
            FLOAT_BUF_16.clear();
            inst.modelView.get(FLOAT_BUF_16);
            for (int f = 0; f < 16; f++) {
                INSTANCE_DATA_BUF.putFloat(FLOAT_BUF_16.get(f));
            }

            // mat4 normalMat
            FLOAT_BUF_16.clear();
            inst.normalMat4.get(FLOAT_BUF_16);
            for (int f = 0; f < 16; f++) {
                INSTANCE_DATA_BUF.putFloat(FLOAT_BUF_16.get(f));
            }

            // uint color, overlay, light, baseVertex
            INSTANCE_DATA_BUF.putInt(inst.color);
            INSTANCE_DATA_BUF.putInt(inst.overlay);
            INSTANCE_DATA_BUF.putInt(inst.light);
            INSTANCE_DATA_BUF.putInt(baseVertices[i]);
        }

        // 3. Dispatch GPU Compute Shader (2D Grid: vertices X instances)
        GpuSkinningShader.get().dispatchInstanced(mesh, count, INSTANCE_DATA_BUF, null);

        // 4. Render primitives using glMultiDrawElementsBaseVertex (1 draw call per material for all N instances!)
        Window window = Minecraft.getInstance().getWindow();
        Matrix4f renderModelView = RenderSystem.getModelViewMatrix();
        Matrix4f renderProj = RenderSystem.getProjectionMatrix();

        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(mesh.vao());

        RenderType activeRenderType = null;
        ShaderInstance activeShader = null;

        for (GpuMesh.GpuPrimitive prim : mesh.primitives()) {
            ResourceLocation texture = prim.material().texture();
            if (key.overrides() != null && key.overrides().containsKey(texture)) {
                texture = key.overrides().get(texture);
            }

            RenderType renderType = ModelSkinningRenderer.resolveRenderType(texture, prim.material(), key.allowVanillaPbr());
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

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer countBuf = stack.mallocInt(count);
                PointerBuffer indicesBuf = stack.mallocPointer(count);
                IntBuffer baseVertexBuf = stack.mallocInt(count);

                int indexCount = prim.indexCount();
                long indexByteOffset = prim.indexByteOffset();

                for (int i = 0; i < count; i++) {
                    countBuf.put(i, indexCount);
                    indicesBuf.put(i, indexByteOffset);
                    baseVertexBuf.put(i, baseVertices[i]);
                }

                GL32.glMultiDrawElementsBaseVertex(
                        GL11.GL_TRIANGLES,
                        countBuf,
                        GL11.GL_UNSIGNED_INT,
                        indicesBuf,
                        baseVertexBuf
                );
            }
        }

        if (activeShader != null) {
            activeShader.clear();
        }
        if (activeRenderType != null) {
            activeRenderType.clearRenderState();
        }

        GL30.glBindVertexArray(prevVao);

        // 5. Signal that the draw calls reading these slots are in-flight
        for (int slot : slots) {
            mesh.markSlotDrawn(slot);
        }
    }
}
