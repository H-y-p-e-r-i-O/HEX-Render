package com.hex.hexrender.loader;

import com.google.gson.*;
import com.hex.hexrender.mesh.PrimitiveMaterial;
import com.hex.hexrender.mesh.SkinnedPrimitive;
import com.hex.hexrender.motion.AnimationTrack;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.hex.hexrender.skeleton.RigHierarchy;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Ultra-compact, high-performance glTF 2.0 and GLB parser with zero external vendor dependencies.
 * Built entirely on standard Java and Minecraft's internal Gson.
 */
public final class GltfMicroParser {

    private GltfMicroParser() {
    }

    public static GltfModelAsset parse(ResourceLocation fileLocation) throws IOException {
        try (InputStream in = Minecraft.getInstance().getResourceManager().getResourceOrThrow(fileLocation).open()) {
            return parse(fileLocation, in);
        }
    }

    public static GltfModelAsset parse(ResourceLocation fileLocation, InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        if (bytes.length < 4) {
            throw new IOException("glTF file is too small or empty: " + fileLocation);
        }

        String jsonText;
        ByteBuffer glbBinaryChunk = null;

        // Check GLB magic 0x46546C67 ("glTF")
        if (bytes[0] == 0x67 && bytes[1] == 0x6C && bytes[2] == 0x54 && bytes[3] == 0x46) {
            if (bytes.length < 12) {
                throw new IOException("Malformed GLB container: header too short");
            }
            int totalLength = readIntLE(bytes, 8);
            int offset = 12;

            int chunk0Len = readIntLE(bytes, offset);
            int chunk0Type = readIntLE(bytes, offset + 4);
            if (chunk0Type != 0x4E4F534A) { // "JSON"
                throw new IOException("GLB chunk 0 is not JSON: 0x" + Integer.toHexString(chunk0Type));
            }
            jsonText = new String(bytes, offset + 8, chunk0Len, StandardCharsets.UTF_8);
            offset += 8 + chunk0Len;

            // Optional BIN chunk
            if (offset + 8 <= totalLength && offset + 8 <= bytes.length) {
                int chunk1Len = readIntLE(bytes, offset);
                int chunk1Type = readIntLE(bytes, offset + 4);
                if (chunk1Type == 0x004E4942) { // "BIN\0"
                    glbBinaryChunk = ByteBuffer.wrap(bytes, offset + 8, chunk1Len).order(ByteOrder.LITTLE_ENDIAN);
                }
            }
        } else {
            jsonText = new String(bytes, StandardCharsets.UTF_8);
        }

        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        return buildAsset(root, glbBinaryChunk, fileLocation);
    }

    private static GltfModelAsset buildAsset(JsonObject root, @Nullable ByteBuffer glbBin, ResourceLocation sourceLoc) throws IOException {
        // 1. Buffers
        List<ByteBuffer> buffers = new ArrayList<>();
        if (root.has("buffers")) {
            JsonArray bufArr = root.getAsJsonArray("buffers");
            for (int i = 0; i < bufArr.size(); i++) {
                JsonObject bObj = bufArr.get(i).getAsJsonObject();
                if (!bObj.has("uri") || bObj.get("uri").isJsonNull()) {
                    if (glbBin != null && i == 0) {
                        buffers.add(glbBin);
                    } else {
                        throw new IOException("Buffer " + i + " has no URI and no GLB binary chunk");
                    }
                } else {
                    String uri = bObj.get("uri").getAsString();
                    if (uri.startsWith("data:")) {
                        int comma = uri.indexOf(',');
                        if (comma < 0) {
                            throw new IOException("Malformed data URI in buffer " + i);
                        }
                        byte[] decoded = Base64.getDecoder().decode(uri.substring(comma + 1));
                        buffers.add(ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN));
                    } else {
                        throw new IOException("External buffer URI not supported: " + uri);
                    }
                }
            }
        }

        // 2. BufferViews
        List<BufferView> bufferViews = new ArrayList<>();
        if (root.has("bufferViews")) {
            JsonArray bvArr = root.getAsJsonArray("bufferViews");
            for (JsonElement e : bvArr) {
                JsonObject bvObj = e.getAsJsonObject();
                int bufIdx = bvObj.get("buffer").getAsInt();
                int byteOffset = bvObj.has("byteOffset") ? bvObj.get("byteOffset").getAsInt() : 0;
                int byteLength = bvObj.get("byteLength").getAsInt();
                int byteStride = bvObj.has("byteStride") ? bvObj.get("byteStride").getAsInt() : 0;

                ByteBuffer buf = buffers.get(bufIdx);
                ByteBuffer slice = buf.slice(byteOffset, byteLength).order(ByteOrder.LITTLE_ENDIAN);
                bufferViews.add(new BufferView(slice, byteStride));
            }
        }

        // 3. Accessors
        List<Accessor> accessors = new ArrayList<>();
        if (root.has("accessors")) {
            JsonArray accArr = root.getAsJsonArray("accessors");
            for (JsonElement e : accArr) {
                JsonObject aObj = e.getAsJsonObject();
                int bvIdx = aObj.has("bufferView") ? aObj.get("bufferView").getAsInt() : -1;
                int byteOffset = aObj.has("byteOffset") ? aObj.get("byteOffset").getAsInt() : 0;
                int componentType = aObj.get("componentType").getAsInt();
                int count = aObj.get("count").getAsInt();
                String type = aObj.get("type").getAsString();

                BufferView bv = bvIdx >= 0 ? bufferViews.get(bvIdx) : null;
                accessors.add(new Accessor(bv, byteOffset, componentType, count, type));
            }
        }

        // 4. Materials
        List<PrimitiveMaterial> materials = new ArrayList<>();
        if (root.has("materials")) {
            JsonArray matArr = root.getAsJsonArray("materials");
            for (JsonElement e : matArr) {
                JsonObject mObj = e.getAsJsonObject();
                materials.add(parseMaterial(mObj, root, sourceLoc));
            }
        }

        // 5. Nodes
        List<NodeData> nodes = new ArrayList<>();
        if (root.has("nodes")) {
            JsonArray nodeArr = root.getAsJsonArray("nodes");
            for (int i = 0; i < nodeArr.size(); i++) {
                JsonObject nObj = nodeArr.get(i).getAsJsonObject();
                String name = nObj.has("name") ? nObj.get("name").getAsString() : "node_" + i;

                Vector3f translation = new Vector3f();
                if (nObj.has("translation")) {
                    JsonArray t = nObj.getAsJsonArray("translation");
                    translation.set(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat());
                }

                Quaternionf rotation = new Quaternionf();
                if (nObj.has("rotation")) {
                    JsonArray r = nObj.getAsJsonArray("rotation");
                    rotation.set(r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat());
                }

                Vector3f scale = new Vector3f(1.0f, 1.0f, 1.0f);
                if (nObj.has("scale")) {
                    JsonArray s = nObj.getAsJsonArray("scale");
                    scale.set(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat());
                }

                List<Integer> children = new ArrayList<>();
                if (nObj.has("children")) {
                    for (JsonElement c : nObj.getAsJsonArray("children")) {
                        children.add(c.getAsInt());
                    }
                }

                int meshIdx = nObj.has("mesh") ? nObj.get("mesh").getAsInt() : -1;
                int skinIdx = nObj.has("skin") ? nObj.get("skin").getAsInt() : -1;
                nodes.add(new NodeData(name, translation, rotation, scale, children, meshIdx, skinIdx));
            }
        }

        int nodeCount = nodes.size();
        String[] nodeNames = new String[nodeCount];
        int[] parentIndices = new int[nodeCount];
        Arrays.fill(parentIndices, -1);
        Vector3f[] restTranslations = new Vector3f[nodeCount];
        Quaternionf[] restRotations = new Quaternionf[nodeCount];
        Vector3f[] restScales = new Vector3f[nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            NodeData nd = nodes.get(i);
            nodeNames[i] = nd.name;
            restTranslations[i] = nd.translation;
            restRotations[i] = nd.rotation;
            restScales[i] = nd.scale;
            for (int childIdx : nd.children) {
                if (childIdx >= 0 && childIdx < nodeCount) {
                    parentIndices[childIdx] = i;
                }
            }
        }

        // 6. Skins
        int[] jointNodeIndices = new int[0];
        Matrix4f[] inverseBindMatrices = new Matrix4f[0];
        if (root.has("skins")) {
            JsonArray skinArr = root.getAsJsonArray("skins");
            if (skinArr.size() > 0) {
                JsonObject sObj = skinArr.get(0).getAsJsonObject();
                JsonArray jointsArr = sObj.getAsJsonArray("joints");
                int jointCount = jointsArr.size();
                jointNodeIndices = new int[jointCount];
                for (int j = 0; j < jointCount; j++) {
                    jointNodeIndices[j] = jointsArr.get(j).getAsInt();
                }

                inverseBindMatrices = new Matrix4f[jointCount];
                if (sObj.has("inverseBindMatrices")) {
                    int ibmAccIdx = sObj.get("inverseBindMatrices").getAsInt();
                    Accessor ibmAcc = accessors.get(ibmAccIdx);
                    for (int j = 0; j < jointCount; j++) {
                        inverseBindMatrices[j] = ibmAcc.getMatrix4f(j);
                    }
                } else {
                    for (int j = 0; j < jointCount; j++) {
                        inverseBindMatrices[j] = new Matrix4f().identity();
                    }
                }
            }
        }

        RigHierarchy rig = new RigHierarchy(
                nodeNames,
                parentIndices,
                restTranslations,
                restRotations,
                restScales,
                jointNodeIndices,
                inverseBindMatrices
        );

        // 7. Meshes
        List<SkinnedPrimitive> primitives = new ArrayList<>();
        if (root.has("meshes")) {
            JsonArray meshArr = root.getAsJsonArray("meshes");
            for (JsonElement mElem : meshArr) {
                JsonObject mObj = mElem.getAsJsonObject();
                if (mObj.has("primitives")) {
                    for (JsonElement pElem : mObj.getAsJsonArray("primitives")) {
                        JsonObject pObj = pElem.getAsJsonObject();
                        primitives.add(parsePrimitive(pObj, accessors, materials, sourceLoc));
                    }
                }
            }
        }

        // 8. Animations
        Map<String, SkeletalAnimation> animations = new HashMap<>();
        if (root.has("animations")) {
            JsonArray animArr = root.getAsJsonArray("animations");
            for (int i = 0; i < animArr.size(); i++) {
                JsonObject aObj = animArr.get(i).getAsJsonObject();
                String name = aObj.has("name") ? aObj.get("name").getAsString() : "anim_" + i;

                JsonArray samplersArr = aObj.getAsJsonArray("samplers");
                List<SamplerData> samplers = new ArrayList<>();
                for (JsonElement sElem : samplersArr) {
                    JsonObject sObj = sElem.getAsJsonObject();
                    int inAccIdx = sObj.get("input").getAsInt();
                    int outAccIdx = sObj.get("output").getAsInt();
                    String interp = sObj.has("interpolation") ? sObj.get("interpolation").getAsString() : "LINEAR";
                    samplers.add(new SamplerData(accessors.get(inAccIdx), accessors.get(outAccIdx), interp));
                }

                JsonArray channelsArr = aObj.getAsJsonArray("channels");
                List<AnimationTrack> tracks = new ArrayList<>();
                float maxTime = 0.0f;

                for (JsonElement cElem : channelsArr) {
                    JsonObject cObj = cElem.getAsJsonObject();
                    int samplerIdx = cObj.get("sampler").getAsInt();
                    JsonObject target = cObj.getAsJsonObject("target");
                    int targetNode = target.has("node") ? target.get("node").getAsInt() : 0;
                    String path = target.get("path").getAsString();

                    AnimationTrack.ChannelType channelType;
                    int components;
                    if ("translation".equalsIgnoreCase(path)) {
                        channelType = AnimationTrack.ChannelType.TRANSLATION;
                        components = 3;
                    } else if ("rotation".equalsIgnoreCase(path)) {
                        channelType = AnimationTrack.ChannelType.ROTATION;
                        components = 4;
                    } else if ("scale".equalsIgnoreCase(path)) {
                        channelType = AnimationTrack.ChannelType.SCALE;
                        components = 3;
                    } else {
                        continue;
                    }

                    SamplerData sData = samplers.get(samplerIdx);
                    AnimationTrack.Interpolation interpolation;
                    if ("STEP".equalsIgnoreCase(sData.interpolation)) {
                        interpolation = AnimationTrack.Interpolation.STEP;
                    } else if ("CUBICSPLINE".equalsIgnoreCase(sData.interpolation)) {
                        interpolation = AnimationTrack.Interpolation.CUBICSPLINE;
                    } else {
                        interpolation = AnimationTrack.Interpolation.LINEAR;
                    }

                    int keyframeCount = sData.input.count;
                    float[] timestamps = new float[keyframeCount];
                    for (int k = 0; k < keyframeCount; k++) {
                        timestamps[k] = sData.input.getFloat(k, 0);
                        if (timestamps[k] > maxTime) {
                            maxTime = timestamps[k];
                        }
                    }

                    float[] values = new float[keyframeCount * components];
                    for (int k = 0; k < keyframeCount; k++) {
                        for (int c = 0; c < components; c++) {
                            values[k * components + c] = sData.output.getFloat(k, c);
                        }
                    }

                    tracks.add(new AnimationTrack(targetNode, channelType, interpolation, timestamps, values));
                }

                animations.put(name, new SkeletalAnimation(name, maxTime, tracks));
            }
        }

        Map<String, Vector3f> sockets = new HashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            if (nodeNames[i] != null && nodeNames[i].startsWith("socket_")) {
                sockets.put(nodeNames[i], restTranslations[i]);
            }
        }
        return new GltfModelAsset(sourceLoc, primitives, rig, animations, sockets);
    }

    private static SkinnedPrimitive parsePrimitive(
            JsonObject pObj,
            List<Accessor> accessors,
            List<PrimitiveMaterial> materials,
            ResourceLocation sourceLoc
    ) {
        JsonObject attrs = pObj.getAsJsonObject("attributes");
        Accessor posAcc = attrs.has("POSITION") ? accessors.get(attrs.get("POSITION").getAsInt()) : null;
        int vertexCount = posAcc != null ? posAcc.count : 0;

        float[] positions = new float[vertexCount * 3];
        if (posAcc != null) {
            for (int v = 0; v < vertexCount; v++) {
                int base = v * 3;
                positions[base] = posAcc.getFloat(v, 0);
                positions[base + 1] = posAcc.getFloat(v, 1);
                positions[base + 2] = posAcc.getFloat(v, 2);
            }
        }

        float[] normals = new float[vertexCount * 3];
        if (attrs.has("NORMAL")) {
            Accessor normAcc = accessors.get(attrs.get("NORMAL").getAsInt());
            for (int v = 0; v < vertexCount; v++) {
                int base = v * 3;
                normals[base] = normAcc.getFloat(v, 0);
                normals[base + 1] = normAcc.getFloat(v, 1);
                normals[base + 2] = normAcc.getFloat(v, 2);
            }
        } else {
            for (int v = 0; v < vertexCount; v++) {
                normals[v * 3 + 1] = 1.0f; // default Up normal
            }
        }

        float[] uvs = new float[vertexCount * 2];
        if (attrs.has("TEXCOORD_0")) {
            Accessor uvAcc = accessors.get(attrs.get("TEXCOORD_0").getAsInt());
            for (int v = 0; v < vertexCount; v++) {
                int base = v * 2;
                uvs[base] = uvAcc.getFloat(v, 0);
                uvs[base + 1] = uvAcc.getFloat(v, 1);
            }
        }

        float[] weights = null;
        int[] joints = null;
        if (attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0")) {
            Accessor jointsAcc = accessors.get(attrs.get("JOINTS_0").getAsInt());
            Accessor weightsAcc = accessors.get(attrs.get("WEIGHTS_0").getAsInt());

            weights = new float[vertexCount * 4];
            joints = new int[vertexCount * 4];

            for (int v = 0; v < vertexCount; v++) {
                int base = v * 4;
                float w0 = weightsAcc.getFloat(v, 0);
                float w1 = weightsAcc.getFloat(v, 1);
                float w2 = weightsAcc.getFloat(v, 2);
                float w3 = weightsAcc.getFloat(v, 3);

                float sum = w0 + w1 + w2 + w3;
                if (sum > 1e-5f) {
                    float inv = 1.0f / sum;
                    w0 *= inv;
                    w1 *= inv;
                    w2 *= inv;
                    w3 *= inv;
                } else {
                    w0 = 1.0f;
                    w1 = 0.0f;
                    w2 = 0.0f;
                    w3 = 0.0f;
                }

                weights[base] = w0;
                weights[base + 1] = w1;
                weights[base + 2] = w2;
                weights[base + 3] = w3;

                joints[base] = jointsAcc.getInt(v, 0);
                joints[base + 1] = jointsAcc.getInt(v, 1);
                joints[base + 2] = jointsAcc.getInt(v, 2);
                joints[base + 3] = jointsAcc.getInt(v, 3);
            }
        }

        int[] indices;
        if (pObj.has("indices")) {
            Accessor indAcc = accessors.get(pObj.get("indices").getAsInt());
            indices = new int[indAcc.count];
            for (int i = 0; i < indAcc.count; i++) {
                indices[i] = indAcc.getInt(i, 0);
            }
        } else {
            indices = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                indices[i] = i;
            }
        }

        PrimitiveMaterial material;
        if (pObj.has("material") && materials.size() > pObj.get("material").getAsInt()) {
            material = materials.get(pObj.get("material").getAsInt());
        } else {
            material = PrimitiveMaterial.of(fallbackTexture(sourceLoc), false, false);
        }

        return new SkinnedPrimitive(positions, normals, uvs, weights, joints, indices, vertexCount, indices.length, material);
    }

    private static PrimitiveMaterial parseMaterial(JsonObject mObj, JsonObject root, ResourceLocation sourceLoc) {
        ResourceLocation texture = null;

        // Try extracting texture from extras.resourceLocation
        texture = extractResourceLocation(mObj);

        if (texture == null && mObj.has("pbrMetallicRoughness")) {
            JsonObject pbr = mObj.getAsJsonObject("pbrMetallicRoughness");
            if (pbr.has("baseColorTexture")) {
                int texIdx = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                if (root.has("textures") && root.getAsJsonArray("textures").size() > texIdx) {
                    JsonObject tObj = root.getAsJsonArray("textures").get(texIdx).getAsJsonObject();
                    texture = extractResourceLocation(tObj);
                    if (texture == null && tObj.has("source") && root.has("images")) {
                        int imgIdx = tObj.get("source").getAsInt();
                        if (root.getAsJsonArray("images").size() > imgIdx) {
                            JsonObject iObj = root.getAsJsonArray("images").get(imgIdx).getAsJsonObject();
                            texture = extractResourceLocation(iObj);
                        }
                    }
                }
            }
        }

        if (texture == null) {
            texture = fallbackTexture(sourceLoc);
        }

        float roughness = 0.35f;
        float metallic = 0.20f;
        if (mObj.has("pbrMetallicRoughness")) {
            JsonObject pbr = mObj.getAsJsonObject("pbrMetallicRoughness");
            if (pbr.has("roughnessFactor")) roughness = pbr.get("roughnessFactor").getAsFloat();
            if (pbr.has("metallicFactor")) metallic = pbr.get("metallicFactor").getAsFloat();
        }

        boolean doubleSided = mObj.has("doubleSided") && mObj.get("doubleSided").getAsBoolean();
        PrimitiveMaterial.AlphaType alphaType = PrimitiveMaterial.AlphaType.MASK;
        float alphaCutoff = 0.1f;
        if (mObj.has("alphaMode")) {
            String mode = mObj.get("alphaMode").getAsString();
            if ("BLEND".equalsIgnoreCase(mode)) {
                alphaType = PrimitiveMaterial.AlphaType.BLEND;
                alphaCutoff = 0.0f;
            } else if ("OPAQUE".equalsIgnoreCase(mode)) {
                alphaType = PrimitiveMaterial.AlphaType.OPAQUE;
                alphaCutoff = 0.0f;
            }
        }
        if (mObj.has("alphaCutoff")) {
            alphaCutoff = mObj.get("alphaCutoff").getAsFloat();
        }

        return new PrimitiveMaterial(texture, alphaType, alphaCutoff, doubleSided, roughness, metallic);
    }

    private static @Nullable ResourceLocation extractResourceLocation(JsonObject obj) {
        if (obj.has("extras") && obj.get("extras").isJsonObject()) {
            JsonObject extras = obj.getAsJsonObject("extras");
            if (extras.has("resourceLocation")) {
                String str = extras.get("resourceLocation").getAsString();
                return ResourceLocation.tryParse(str);
            }
        }
        return null;
    }

    private static ResourceLocation fallbackTexture(ResourceLocation gltfLoc) {
        String path = gltfLoc.getPath();
        if (path.endsWith(".gltf")) {
            path = path.substring(0, path.length() - 5) + ".png";
        } else if (path.endsWith(".glb")) {
            path = path.substring(0, path.length() - 4) + ".png";
        } else {
            path = path + ".png";
        }

        if (path.startsWith("models/item/")) {
            path = "textures/item/" + path.substring("models/item/".length());
        } else if (path.startsWith("models/entity/")) {
            path = "textures/entity/" + path.substring("models/entity/".length());
        } else if (path.startsWith("models/")) {
            path = "textures/" + path.substring("models/".length());
        }

        return ResourceLocation.fromNamespaceAndPath(gltfLoc.getNamespace(), path);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int componentByteSize(int componentType) {
        return switch (componentType) {
            case 5120, 5121 -> 1; // BYTE, UBYTE
            case 5122, 5123 -> 2; // SHORT, USHORT
            case 5125, 5126 -> 4; // UINT, FLOAT
            default -> 4;
        };
    }

    private static int numComponents(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> 1;
        };
    }

    private record BufferView(ByteBuffer data, int stride) {}

    private static final class Accessor {
        final @Nullable BufferView bufferView;
        final int byteOffset;
        final int componentType;
        final int count;
        final String type;
        final int numComponents;
        final int componentSize;
        final int effectiveStride;

        Accessor(@Nullable BufferView bufferView, int byteOffset, int componentType, int count, String type) {
            this.bufferView = bufferView;
            this.byteOffset = byteOffset;
            this.componentType = componentType;
            this.count = count;
            this.type = type;
            this.numComponents = numComponents(type);
            this.componentSize = componentByteSize(componentType);
            this.effectiveStride = (bufferView != null && bufferView.stride > 0)
                    ? bufferView.stride
                    : this.numComponents * this.componentSize;
        }

        float getFloat(int elemIdx, int compIdx) {
            if (bufferView == null) return 0.0f;
            int pos = byteOffset + elemIdx * effectiveStride + compIdx * componentSize;
            return switch (componentType) {
                case 5126 -> bufferView.data.getFloat(pos);
                case 5123 -> Short.toUnsignedInt(bufferView.data.getShort(pos)) / 65535.0f;
                case 5121 -> Byte.toUnsignedInt(bufferView.data.get(pos)) / 255.0f;
                case 5122 -> Math.max(bufferView.data.getShort(pos) / 32767.0f, -1.0f);
                case 5120 -> Math.max(bufferView.data.get(pos) / 127.0f, -1.0f);
                default -> 0.0f;
            };
        }

        int getInt(int elemIdx, int compIdx) {
            if (bufferView == null) return 0;
            int pos = byteOffset + elemIdx * effectiveStride + compIdx * componentSize;
            return switch (componentType) {
                case 5123 -> Short.toUnsignedInt(bufferView.data.getShort(pos));
                case 5121 -> Byte.toUnsignedInt(bufferView.data.get(pos));
                case 5122 -> bufferView.data.getShort(pos);
                case 5120 -> bufferView.data.get(pos);
                case 5125, 5124 -> bufferView.data.getInt(pos);
                default -> (int) bufferView.data.getFloat(pos);
            };
        }

        Matrix4f getMatrix4f(int elemIdx) {
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) {
                m[i] = getFloat(elemIdx, i);
            }
            return new Matrix4f().set(m);
        }
    }

    private record NodeData(
            String name,
            Vector3f translation,
            Quaternionf rotation,
            Vector3f scale,
            List<Integer> children,
            int mesh,
            int skin
    ) {}

    private record SamplerData(Accessor input, Accessor output, String interpolation) {}
}
