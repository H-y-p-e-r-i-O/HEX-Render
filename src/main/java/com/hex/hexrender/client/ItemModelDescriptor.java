package com.hex.hexrender.client;

import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Strategy interface describing how an item looks and animates in various contexts (hand, GUI, ground, etc.).
 */
public interface ItemModelDescriptor {

    /**
     * Resolves the glTF model for this stack and display context.
     */
    @Nullable
    GltfModelAsset model(ItemStack stack, ItemDisplayContext context);

    /**
     * Resolves the active animation clip, or null for rest pose.
     */
    @Nullable
    default SkeletalAnimation clip(ItemStack stack, ItemDisplayContext context) {
        return null;
    }

    /**
     * Current playback time in seconds.
     */
    default float seconds(ItemStack stack, ItemDisplayContext context, float partialTick) {
        return 0.0f;
    }

    /**
     * Context-specific matrix transformations (e.g. scale, translation, rotation).
     */
    default void transform(ItemStack stack, ItemDisplayContext context, PoseStack pose) {
    }

    /**
     * Vertex color tint in ARGB format (0xFFFFFFFF = white / no tint).
     */
    default int tint(ItemStack stack, ItemDisplayContext context) {
        return 0xFFFFFFFF;
    }

    /**
     * Texture override map (e.g. for variant skins or customizable attachments).
     */
    @Nullable
    default Map<ResourceLocation, ResourceLocation> textureOverrides(ItemStack stack, ItemDisplayContext context) {
        return null;
    }
}
