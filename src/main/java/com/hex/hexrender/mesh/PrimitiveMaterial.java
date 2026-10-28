package com.hex.hexrender.mesh;

import net.minecraft.resources.ResourceLocation;

/**
 * Surface shading parameters for a model mesh primitive.
 */
public record PrimitiveMaterial(
        ResourceLocation texture,
        AlphaType alphaType,
        float alphaCutoff,
        boolean doubleSided,
        float roughness,
        float metallic
) {
    public enum AlphaType {
        OPAQUE,
        MASK,
        BLEND
    }

    public PrimitiveMaterial(
            ResourceLocation texture,
            AlphaType alphaType,
            float alphaCutoff,
            boolean doubleSided
    ) {
        this(texture, alphaType, alphaCutoff, doubleSided, 0.4f, 0.2f);
    }

    public static PrimitiveMaterial of(ResourceLocation texture, boolean blended, boolean doubleSided) {
        return new PrimitiveMaterial(
                texture,
                blended ? AlphaType.BLEND : AlphaType.MASK,
                blended ? 0.0f : 0.1f,
                doubleSided,
                0.4f,
                0.2f
        );
    }
}
