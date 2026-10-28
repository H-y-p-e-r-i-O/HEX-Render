package com.hex.hexrender.core;

import com.hex.hexrender.loader.GltfModelAsset;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Type-safe handle to an asynchronously loaded glTF model asset.
 */
public class ModelHandle implements Supplier<GltfModelAsset> {
    private final ResourceLocation id;
    private final Supplier<GltfModelAsset> resolver;

    public ModelHandle(ResourceLocation id, Supplier<GltfModelAsset> resolver) {
        this.id = id;
        this.resolver = resolver;
    }

    public ResourceLocation id() {
        return id;
    }

    @Nullable
    @Override
    public GltfModelAsset get() {
        return resolver.get();
    }

    public boolean isLoaded() {
        return get() != null;
    }
}
