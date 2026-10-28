package com.hex.hexrender.client;

import com.hex.hexrender.loader.GltfModelAsset;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Strategy interface describing how worn glTF armor appears on an entity.
 */
public interface ArmorModelDescriptor {

    @Nullable
    GltfModelAsset model(LivingEntity entity, ItemStack stack, EquipmentSlot slot);

    default int tint(LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
        return 0xFFFFFFFF;
    }

    @Nullable
    default Map<ResourceLocation, ResourceLocation> textureOverrides(LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
        return null;
    }
}
