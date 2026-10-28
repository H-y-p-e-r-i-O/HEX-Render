package com.hex.hexrender.core;

import com.hex.hexrender.HexRender;
import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.loader.GltfSceneLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Universal async asset manager and cache for glTF 2.0 models.
 */
public final class ModelRegistry {
    private static final ConcurrentHashMap<ResourceLocation, GltfModelAsset> LOADED_MODELS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceLocation, CompletableFuture<GltfModelAsset>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger(0);

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "HEXRender-AssetWorker");
                t.setDaemon(true);
                return t;
            }
    );

    private ModelRegistry() {
    }

    public static int generation() {
        return GENERATION.get();
    }

    @Nullable
    public static GltfModelAsset fetch(ResourceLocation location) {
        GltfModelAsset existing = LOADED_MODELS.get(location);
        if (existing != null) {
            return existing;
        }

        IN_FLIGHT.computeIfAbsent(location, loc -> CompletableFuture.supplyAsync(() -> {
            try {
                var resourceManager = Minecraft.getInstance().getResourceManager();
                var resourceOpt = resourceManager.getResource(loc);
                if (resourceOpt.isEmpty()) {
                    HexRender.LOGGER.error("glTF model not found: {}", loc);
                    return null;
                }
                try (InputStream stream = resourceOpt.get().open()) {
                    GltfModelAsset asset = GltfSceneLoader.load(loc, stream);
                    if (asset != null) {
                        LOADED_MODELS.put(loc, asset);
                        HexRender.LOGGER.info("Successfully loaded glTF asset: {}", loc);
                    }
                    return asset;
                }
            } catch (Exception e) {
                HexRender.LOGGER.error("Failed to parse glTF asset: " + loc, e);
                return null;
            }
        }, WORKER).whenComplete((res, ex) -> IN_FLIGHT.remove(loc)));

        return null;
    }

    public static void preload(ResourceLocation location) {
        fetch(location);
    }

    @Nullable
    public static GltfModelAsset obtain(ResourceLocation location) {
        return fetch(location);
    }

    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            reload();
        });
    }

    public static void reload() {
        GENERATION.incrementAndGet();
        LOADED_MODELS.clear();
        IN_FLIGHT.clear();
        HexRender.LOGGER.info("HEX Render ModelRegistry invalidated. Reloading requested models...");
    }
}
