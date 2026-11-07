package com.hex.hexrender.client;

import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Declarative description of a glTF model, transformations, and animations for a {@link BlockEntity}.
 *
 * @param <T> The BlockEntity type
 */
public interface BlockEntityModelDescriptor<T extends BlockEntity> {

    /**
     * Resolves the glTF model asset for this block entity.
     */
    @Nullable
    GltfModelAsset model(T blockEntity);

    /**
     * Optional simplified low-poly proxy model used exclusively during Iris/Oculus shadow pass.
     * <p>
     * Casting shadows from 50-polygon bounding hulls/cylinders instead of 10,000+ polygon machines
     * yields visually identical contact shadows while slashing shadow pass GPU overhead by up to 98%.
     * Defaults to null (uses {@link #model(BlockEntity)}).
     */
    @Nullable
    default GltfModelAsset shadowModel(T blockEntity) {
        return null;
    }

    /**
     * Whether this block entity casts dynamic shadows in Iris/Oculus shaderpacks.
     */
    default boolean castsShadows(T blockEntity) {
        return true;
    }

    /**
     * Whether to enable the custom Blinn-Phong specular shader in vanilla (no-shader) mode.
     * <p>
     * Defaults to false for block entities because vanilla OpenGL lacks shadow mapping, which causes
     * highlights to appear on surfaces occluded by roofs and overhangs. With Iris shaders, physical PBR
     * is handled by the shaderpack with 100% accurate contact shadows regardless of this setting.
     */
    default boolean enableVanillaPbr(T blockEntity) {
        return false;
    }

    /**
     * Bounding radius in blocks around the block position for zero-latency camera plane culling.
     */
    default double boundingRadius(T blockEntity) {
        return 6.0;
    }

    /**
     * Maximum distance in blocks for casting dynamic shadows.
     * Block entities further than this distance will be culled from the shadow map pass, saving GPU cycles.
     */
    default double shadowDistance(T blockEntity) {
        return 32.0;
    }

    /**
     * Maximum view distance in blocks for rendering this block entity.
     * Defaults to Integer.MAX_VALUE so it renders as far as the player's render distance without artificial limits.
     */
    default int viewDistance(T blockEntity) {
        return Integer.MAX_VALUE;
    }

    /**
     * Applies custom transformations (translations, rotations, scaling) before rendering.
     */
    default void transform(T blockEntity, float partialTick, PoseStack poseStack) {
    }

    /**
     * Resolves the active animation clip for this block entity, or null if unanimated/idle.
     */
    @Nullable
    default SkeletalAnimation clip(T blockEntity) {
        return null;
    }

    /**
     * Calculates the animation playback time in seconds.
     */
    default float seconds(T blockEntity, float partialTick) {
        if (blockEntity.getLevel() != null) {
            return (blockEntity.getLevel().getGameTime() + partialTick) / 20.0f;
        }
        return 0.0f;
    }

    /**
     * ARGB tint applied to the model (0xFFFFFFFF = full white/no tint).
     */
    default int tint(T blockEntity) {
        return 0xFFFFFFFF;
    }

    /**
     * Optional texture replacement map.
     */
    @Nullable
    default Map<ResourceLocation, ResourceLocation> textureOverrides(T blockEntity) {
        return null;
    }

    /**
     * Render bounding box expansion around the block position to prevent camera frustum culling.
     */
    default AABB renderBoundingBox(T blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(6.0);
    }
}
