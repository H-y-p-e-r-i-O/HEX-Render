package com.hex.hexrender.loader;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;

/**
 * High-performance loader that reads glTF 2.0 / GLB files into {@link GltfModelAsset}.
 */
public final class GltfSceneLoader {

    private GltfSceneLoader() {
    }

    public static GltfModelAsset load(ResourceLocation fileLocation) throws IOException {
        return GltfMicroParser.parse(fileLocation);
    }

    public static GltfModelAsset load(ResourceLocation fileLocation, InputStream in) throws IOException {
        return GltfMicroParser.parse(fileLocation, in);
    }
}
