package com.hex.hexrender.client;

import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.hex.hexrender.render.ModelSkinningRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * Universal item renderer for glTF 2.0 models, handling centered coordinates, animations, and anti-bobbing.
 */
public class GltfItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final ItemModelDescriptor descriptor;

    public GltfItemRenderer(ItemModelDescriptor descriptor) {
        super(
                Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels()
        );
        this.descriptor = descriptor;
    }

    public ItemModelDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void renderByItem(
            ItemStack stack,
            ItemDisplayContext context,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay
    ) {
        if (context.firstPerson() && com.hex.hexrender.direct.HexIrisBridge.isShadowPassActive()) {
            return;
        }

        GltfModelAsset model = descriptor.model(stack, context);
        if (model == null) {
            return;
        }

        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);

        poseStack.pushPose();
        try {
            // Compensate for vanilla (0.5, 0.5, 0.5) corner offset so glTF model origin is preserved at center
            poseStack.translate(0.5f, 0.5f, 0.5f);

            // Apply custom transforms from descriptor
            descriptor.transform(stack, context, poseStack);

            // Evaluate animation pose
            SkeletalAnimation clip = descriptor.clip(stack, context);
            float seconds = descriptor.seconds(stack, context, partialTick);

            Matrix4f[] palette = null;
            if (model.jointCount() > 0) {
                palette = model.newPalette();
                model.evaluatePose(clip, seconds, palette);
            }

            int tint = descriptor.tint(stack, context);
            Map<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> overrides =
                    descriptor.textureOverrides(stack, context);

            ModelSkinningRenderer.renderModel(
                    model,
                    palette,
                    poseStack,
                    bufferSource,
                    light,
                    overlay,
                    tint,
                    overrides
            );
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Mathematically counteracts the vanilla view-bobbing camera oscillation for precision weapons.
     */
    public static void applyAntiBobbing(LocalPlayer player, PoseStack poseStack, float partialTick) {
        float f = player.walkDist - player.walkDistO;
        float f1 = -(player.walkDist + f * partialTick);
        float f2 = Mth.lerp(partialTick, player.oBob, player.bob);

        float tx = Mth.sin(f1 * (float) Math.PI) * f2 * 0.5F;
        float ty = -Math.abs(Mth.cos(f1 * (float) Math.PI) * f2);
        float rz = Mth.sin(f1 * (float) Math.PI) * f2 * 3.0F;
        float rx = Math.abs(Mth.cos(f1 * (float) Math.PI - 0.2F) * f2) * 5.0F;

        poseStack.mulPose(Axis.XP.rotationDegrees(-rx));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-rz));
        poseStack.translate(-tx, -ty, 0.0F);
    }
}
