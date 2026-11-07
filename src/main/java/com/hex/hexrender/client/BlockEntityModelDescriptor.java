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

    /**
     * Maximum view distance in blocks for rendering this block entity.
     */
    default int viewDistance(T blockEntity) {
        return 128;
    }
}
