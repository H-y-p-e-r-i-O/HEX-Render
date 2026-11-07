package com.hex.hexrender.gpu;

import com.hex.hexrender.core.ModelRegistry;
import com.hex.hexrender.loader.GltfModelAsset;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle and caching of VRAM-resident {@link GpuMesh} instances.
 */
public final class GpuModelManager {

    private static final Map<GltfModelAsset, GpuMesh> CACHE = new ConcurrentHashMap<>();
    private static int lastGeneration = -1;

    private GpuModelManager() {
    }

    public static GpuMesh getOrCreate(GltfModelAsset asset) {
        int currentGen = ModelRegistry.generation();
        if (currentGen != lastGeneration) {
            clear();
            lastGeneration = currentGen;
        }

        return CACHE.computeIfAbsent(asset, GpuMesh::create);
    }

    public static synchronized void clear() {
        for (GpuMesh mesh : CACHE.values()) {
            mesh.close();
        }
        CACHE.clear();
    }
}
