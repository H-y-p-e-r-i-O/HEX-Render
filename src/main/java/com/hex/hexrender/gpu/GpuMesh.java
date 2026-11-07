package com.hex.hexrender.gpu;

import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.mesh.PrimitiveMaterial;
import com.hex.hexrender.mesh.SkinnedPrimitive;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 100% Pure GPU VRAM representation of a glTF model.
 * <p>
 * Zero CPU vertex streaming: geometry is pre-computed directly in VRAM via GLSL 4.3 Compute Shaders
 * and rendered from a 256-slot streaming Ring Buffer with hardware fence synchronization.
 */
public final class GpuMesh implements AutoCloseable {

    public record GpuPrimitive(
            int indexCount,
            int indexByteOffset,
            PrimitiveMaterial material
    ) {}

    public static final int VERTEX_IN_STRIDE = 80;  // 80 bytes per input vertex in std430
    public static final int VERTEX_OUT_STRIDE = 36; // 36 bytes per output vertex (NEW_ENTITY)
    public static final int RING_SLOTS = 256;       // 256 streaming buffer slots to handle high-count multiblock installations

    private final int ssboIn;
    private final int ebo;
    private final int vboOut;
    private final int vao;
    private final int totalVertices;
    private final boolean hasBones;
    private final List<GpuPrimitive> primitives;

    private final long[] slotFences = new long[RING_SLOTS];
    private int currentSlot = 0;
    private int lastSlot = 0;
    private boolean closed;

    private GpuMesh(
            int ssboIn,
            int ebo,
            int vboOut,
            int vao,
            int totalVertices,
            boolean hasBones,
            List<GpuPrimitive> primitives
    ) {
        this.ssboIn = ssboIn;
        this.ebo = ebo;
        this.vboOut = vboOut;
        this.vao = vao;
        this.totalVertices = totalVertices;
        this.hasBones = hasBones;
        this.primitives = primitives;
    }

    public static GpuMesh create(GltfModelAsset model) {
        List<SkinnedPrimitive> primList = model.primitives();
        int totalVerts = 0;
        int totalInds = 0;
        boolean anyBones = model.jointCount() > 0;

        for (SkinnedPrimitive prim : primList) {
            totalVerts += prim.vertexCount();
            totalInds += prim.indexCount();
        }

        if (totalVerts == 0 || totalInds == 0) {
            return new GpuMesh(0, 0, 0, 0, 0, false, List.of());
        }

        // 1. Pack InBuffer (SSBO 0)
        ByteBuffer inBuf = BufferUtils.createByteBuffer(totalVerts * VERTEX_IN_STRIDE).order(ByteOrder.nativeOrder());
        ByteBuffer eboBuf = BufferUtils.createByteBuffer(totalInds * 4).order(ByteOrder.nativeOrder());
        List<GpuPrimitive> gpuPrimitives = new ArrayList<>(primList.size());

        int currentBaseVertex = 0;
        int currentEboByteOffset = 0;

        for (SkinnedPrimitive prim : primList) {
            int vCount = prim.vertexCount();
            float[] pos = prim.positions();
            float[] norm = prim.normals();
            float[] uvs = prim.uvs();
            float[] weights = prim.weights();
            int[] joints = prim.joints();

            boolean primHasWeights = weights != null && joints != null;

            for (int v = 0; v < vCount; v++) {
                int b3 = v * 3;
                int b2 = v * 2;
                int b4 = v * 4;

                // pos: vec4 (16 bytes)
                inBuf.putFloat(pos[b3]);
                inBuf.putFloat(pos[b3 + 1]);
                inBuf.putFloat(pos[b3 + 2]);
                inBuf.putFloat(1.0f);

                // normal: vec4 (16 bytes)
                inBuf.putFloat(norm[b3]);
                inBuf.putFloat(norm[b3 + 1]);
                inBuf.putFloat(norm[b3 + 2]);
                inBuf.putFloat(0.0f);

                // uv: vec2 (8 bytes)
                inBuf.putFloat(uvs[b2]);
                inBuf.putFloat(uvs[b2 + 1]);

                // pad0: vec2 (8 bytes for std430 16-byte alignment)
                inBuf.putFloat(0.0f);
                inBuf.putFloat(0.0f);

                // joints: ivec4 (16 bytes)
                if (primHasWeights) {
                    inBuf.putInt(joints[b4]);
                    inBuf.putInt(joints[b4 + 1]);
                    inBuf.putInt(joints[b4 + 2]);
                    inBuf.putInt(joints[b4 + 3]);
                } else {
                    inBuf.putInt(0);
                    inBuf.putInt(0);
                    inBuf.putInt(0);
                    inBuf.putInt(0);
                }

                // weights: vec4 (16 bytes)
                if (primHasWeights) {
                    inBuf.putFloat(weights[b4]);
                    inBuf.putFloat(weights[b4 + 1]);
                    inBuf.putFloat(weights[b4 + 2]);
                    inBuf.putFloat(weights[b4 + 3]);
                } else {
                    inBuf.putFloat(0.0f);
                    inBuf.putFloat(0.0f);
                    inBuf.putFloat(0.0f);
                    inBuf.putFloat(0.0f);
                }
            }

            int[] indices = prim.indices();
            int iCount = prim.indexCount();
            for (int i = 0; i < iCount; i++) {
                eboBuf.putInt(indices[i] + currentBaseVertex);
            }

            gpuPrimitives.add(new GpuPrimitive(iCount, currentEboByteOffset, prim.material()));
            currentBaseVertex += vCount;
            currentEboByteOffset += iCount * 4;
        }

        inBuf.flip();
        eboBuf.flip();

        // 2. Upload SSBO In (Static model definition in VRAM)
        int ssbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, inBuf, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        // 3. Upload EBO (Static index buffer in VRAM)
        int eboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboBuf, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        // 4. Allocate Dynamic Ring-Buffer VBO Out (256 slots in VRAM)
        int vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) totalVerts * VERTEX_OUT_STRIDE * RING_SLOTS, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // 5. Configure VAO matching DefaultVertexFormat.NEW_ENTITY (36-byte stride) with ALL arrays enabled
        int vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);

        // Attr 0: Position (vec3, 12 bytes)
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, VERTEX_OUT_STRIDE, 0);

        // Attr 1: Color (RGBA8, 4 bytes normalized)
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_OUT_STRIDE, 12);

        // Attr 2: UV0 (vec2, 8 bytes)
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, VERTEX_OUT_STRIDE, 16);

        // Attr 3: UV1 Overlay (ivec2, 2 shorts = 4 bytes)
        GL30.glEnableVertexAttribArray(3);
        GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, VERTEX_OUT_STRIDE, 24);

        // Attr 4: UV2 Light (ivec2, 2 shorts = 4 bytes)
        GL30.glEnableVertexAttribArray(4);
        GL30.glVertexAttribIPointer(4, 2, GL11.GL_SHORT, VERTEX_OUT_STRIDE, 28);

        // Attr 5: Normal (vec3, 3 signed bytes normalized = 4 bytes with pad)
        GL20.glEnableVertexAttribArray(5);
        GL20.glVertexAttribPointer(5, 3, GL11.GL_BYTE, true, VERTEX_OUT_STRIDE, 32);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        return new GpuMesh(ssbo, eboId, vbo, vaoId, totalVerts, anyBones, gpuPrimitives);
    }

    /**
     * Cycles to the next Ring Buffer slot and returns its base vertex offset.
     * If the target slot still has draw calls in flight on the GPU from a previous frame, waits for the GPU fence.
     */
    public synchronized int nextBaseVertex() {
        if (totalVertices == 0) return 0;
        int slot = currentSlot;
        long fence = slotFences[slot];
        if (fence != 0) {
            GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            GL32.glDeleteSync(fence);
            slotFences[slot] = 0;
        }

        lastSlot = slot;
        int bv = slot * totalVertices;
        currentSlot = (currentSlot + 1) % RING_SLOTS;
        return bv;
    }

    /**
     * Returns the slot index allocated by the most recent call to {@link #nextBaseVertex()}.
     */
    public synchronized int lastSlot() {
        return lastSlot;
    }

    /**
     * Inserts an execution fence after issuing draw calls for a specific Ring Buffer slot.
     */
    public synchronized void markSlotDrawn(int slot) {
        if (slot >= 0 && slot < RING_SLOTS) {
            if (slotFences[slot] != 0) {
                GL32.glDeleteSync(slotFences[slot]);
            }
            slotFences[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
    }

    public int ssboIn() {
        return ssboIn;
    }

    public int ebo() {
        return ebo;
    }

    public int vboOut() {
        return vboOut;
    }

    public int vao() {
        return vao;
    }

    public int totalVertices() {
        return totalVertices;
    }

    public boolean hasBones() {
        return hasBones;
    }

    public List<GpuPrimitive> primitives() {
        return primitives;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < RING_SLOTS; i++) {
            if (slotFences[i] != 0) {
                GL32.glDeleteSync(slotFences[i]);
                slotFences[i] = 0;
            }
        }
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vboOut != 0) GL15.glDeleteBuffers(vboOut);
        if (ebo != 0) GL15.glDeleteBuffers(ebo);
        if (ssboIn != 0) GL15.glDeleteBuffers(ssboIn);
    }
}
