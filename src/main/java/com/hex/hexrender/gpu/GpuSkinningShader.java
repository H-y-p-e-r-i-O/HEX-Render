package com.hex.hexrender.gpu;

import com.hex.hexrender.HexRender;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Compiles, links and manages the GLSL 4.3 Compute Shader for high-performance GPU skinning.
 */
public final class GpuSkinningShader implements AutoCloseable {

    private static GpuSkinningShader INSTANCE;

    public static final int MAX_BONES = 128;

    private final int programId;
    private final int uboBones;
    private final int ssboInstances;

    // Uniform locations
    private final int locModelView;
    private final int locNormal;
    private final int locTotalVertices;
    private final int locBaseVertex;
    private final int locHasBones;
    private final int locColor;
    private final int locOverlay;
    private final int locLight;
    private final int locIsInstanced;
    private final int locInstanceCount;

    // Reusable buffers
    private final FloatBuffer mat4Buf = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer mat3Buf = BufferUtils.createFloatBuffer(9);
    private final ByteBuffer bonesBuf = BufferUtils.createByteBuffer(MAX_BONES * 64).order(ByteOrder.nativeOrder());
    private final FloatBuffer bonesFloatBuf = bonesBuf.asFloatBuffer();

    private GpuSkinningShader(int programId, int uboBones, int ssboInstances) {
        this.programId = programId;
        this.uboBones = uboBones;
        this.ssboInstances = ssboInstances;

        this.locModelView = GL20.glGetUniformLocation(programId, "u_ModelViewMat");
        this.locNormal = GL20.glGetUniformLocation(programId, "u_NormalMat");
        this.locTotalVertices = GL20.glGetUniformLocation(programId, "u_TotalVertices");
        this.locBaseVertex = GL20.glGetUniformLocation(programId, "u_BaseVertex");
        this.locHasBones = GL20.glGetUniformLocation(programId, "u_HasBones");
        this.locColor = GL20.glGetUniformLocation(programId, "u_Color");
        this.locOverlay = GL20.glGetUniformLocation(programId, "u_Overlay");
        this.locLight = GL20.glGetUniformLocation(programId, "u_Light");
        this.locIsInstanced = GL20.glGetUniformLocation(programId, "u_IsInstanced");
        this.locInstanceCount = GL20.glGetUniformLocation(programId, "u_InstanceCount");
    }

    public static synchronized GpuSkinningShader get() {
        if (INSTANCE == null) {
            INSTANCE = init();
        }
        return INSTANCE;
    }

    private static GpuSkinningShader init() {
        try {
            String shaderSource;
            try (InputStream in = GpuSkinningShader.class.getResourceAsStream("/assets/hexrender/shaders/compute/skinning.comp")) {
                if (in == null) {
                    throw new IllegalStateException("Missing compute shader: /assets/hexrender/shaders/compute/skinning.comp");
                }
                shaderSource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL20.glShaderSource(shader, shaderSource);
            GL20.glCompileShader(shader);

            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(shader);
                GL20.glDeleteShader(shader);
                throw new RuntimeException("Failed to compile skinning.comp:\n" + log);
            }

            int program = GL20.glCreateProgram();
            GL20.glAttachShader(program, shader);
            GL20.glLinkProgram(program);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program);
                GL20.glDeleteProgram(program);
                GL20.glDeleteShader(shader);
                throw new RuntimeException("Failed to link skinning compute shader:\n" + log);
            }

            GL20.glDeleteShader(shader);

            // Bind Uniform Block for Bones (Binding index 2)
            int blockIdx = GL31.glGetUniformBlockIndex(program, "BoneData");
            if (blockIdx != -1) {
                GL31.glUniformBlockBinding(program, blockIdx, 2);
            }

            int ubo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, ubo);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, (long) MAX_BONES * 64, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

            // Create SSBO for Instance Data (Binding index 3) - 256 instances x 144 bytes = 36KB
            int ssboInst = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboInst);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 256L * 144, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            HexRender.LOGGER.info("Successfully compiled and initialized HEXRender GPU Skinning Compute Shader with Instancing");
            return new GpuSkinningShader(program, ubo, ssboInst);
        } catch (Exception e) {
            HexRender.LOGGER.error("Failed to initialize GpuSkinningShader", e);
            throw new RuntimeException(e);
        }
    }

    public void dispatch(
            GpuMesh mesh,
            int baseVertex,
            Matrix4f modelViewMat,
            Matrix3f normalMat,
            Matrix4f[] bonePalette,
            int color,
            int overlay,
            int light
    ) {
        int vCount = mesh.totalVertices();
        if (vCount == 0) return;

        GL20.glUseProgram(programId);
        GL20.glUniform1i(locIsInstanced, 0);

        // Upload ModelView Matrix
        mat4Buf.clear();
        modelViewMat.get(mat4Buf);
        GL20.glUniformMatrix4fv(locModelView, false, mat4Buf);

        // Upload Normal Matrix
        mat3Buf.clear();
        normalMat.get(mat3Buf);
        GL20.glUniformMatrix3fv(locNormal, false, mat3Buf);

        // Upload Scalar Uniforms
        GL43.glUniform1ui(locTotalVertices, vCount);
        GL43.glUniform1ui(locBaseVertex, baseVertex);
        GL20.glUniform1i(locHasBones, (bonePalette != null && mesh.hasBones()) ? 1 : 0);
        GL43.glUniform1ui(locColor, color);
        GL43.glUniform1ui(locOverlay, overlay);
        GL43.glUniform1ui(locLight, light);

        // Upload Bone Matrices if animated
        if (bonePalette != null && mesh.hasBones()) {
            bonesFloatBuf.clear();
            int count = Math.min(bonePalette.length, MAX_BONES);
            for (int i = 0; i < count; i++) {
                Matrix4f m = bonePalette[i];
                if (m != null) {
                    m.get(i * 16, bonesFloatBuf);
                }
            }
            bonesBuf.position(0).limit(count * 64);

            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboBones);
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, bonesBuf);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 2, uboBones);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        }

        // Bind SSBO 0 (In) & SSBO 1 (Out)
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, mesh.ssboIn());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, mesh.vboOut());

        // Dispatch compute threads
        int groups = (vCount + 63) / 64;
        GL43.glDispatchCompute(groups, 1, 1);

        // Synchronize so subsequent vertex fetch reads updated data
        GL43.glMemoryBarrier(GL43.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

        // Unbind
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
        GL20.glUseProgram(0);
    }

    public void dispatchInstanced(
            GpuMesh mesh,
            int instanceCount,
            ByteBuffer instanceData,
            Matrix4f[] bonePalette
    ) {
        int vCount = mesh.totalVertices();
        if (vCount == 0 || instanceCount == 0) return;

        GL20.glUseProgram(programId);

        GL20.glUniform1i(locIsInstanced, 1);
        GL43.glUniform1ui(locInstanceCount, instanceCount);
        GL43.glUniform1ui(locTotalVertices, vCount);
        GL20.glUniform1i(locHasBones, (bonePalette != null && mesh.hasBones()) ? 1 : 0);

        // Upload bone matrices if animated
        if (bonePalette != null && mesh.hasBones()) {
            bonesFloatBuf.clear();
            int count = Math.min(bonePalette.length, MAX_BONES);
            for (int i = 0; i < count; i++) {
                Matrix4f m = bonePalette[i];
                if (m != null) {
                    m.get(i * 16, bonesFloatBuf);
                }
            }
            bonesBuf.position(0).limit(count * 64);

            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboBones);
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, bonesBuf);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 2, uboBones);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        }

        // Upload instance data to SSBO 3
        instanceData.flip();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboInstances);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, instanceData);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, ssboInstances);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        // Bind SSBO 0 (In) & SSBO 1 (Out)
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, mesh.ssboIn());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, mesh.vboOut());

        // Dispatch 2D grid: X = vertex groups, Y = instances
        int groupsX = (vCount + 63) / 64;
        GL43.glDispatchCompute(groupsX, instanceCount, 1);

        // Synchronize so subsequent vertex fetch reads updated data
        GL43.glMemoryBarrier(GL43.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

        // Unbind
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, 0);
        GL20.glUseProgram(0);
    }

    @Override
    public void close() {
        if (ssboInstances != 0) GL15.glDeleteBuffers(ssboInstances);
        if (uboBones != 0) GL15.glDeleteBuffers(uboBones);
        if (programId != 0) GL20.glDeleteProgram(programId);
    }
}
