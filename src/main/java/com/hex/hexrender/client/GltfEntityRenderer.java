package com.hex.hexrender.client;

import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.hex.hexrender.render.ModelSkinningRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.function.Function;

/**
 * Universal entity/mob/projectile renderer for glTF 2.0 models.
 */
public class GltfEntityRenderer<T extends Entity> extends EntityRenderer<T> {
    private final Function<T, GltfModelAsset> modelProvider;

    public GltfEntityRenderer(EntityRendererProvider.Context context, Function<T, GltfModelAsset> modelProvider) {
        super(context);
        this.modelProvider = modelProvider;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        GltfModelAsset model = modelProvider.apply(entity);
        if (model != null && !model.primitives().isEmpty()) {
            return model.primitives().get(0).material().texture();
        }
        return ResourceLocation.withDefaultNamespace("textures/entity/pig/pig.png");
    }

    @Override
    public void render(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight
    ) {
        GltfModelAsset model = modelProvider.apply(entity);
        if (model == null) {
            return;
        }

        poseStack.pushPose();
        try {
            float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
            float pitch = Mth.rotLerp(partialTick, entity.xRotO, entity.getXRot());

            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));

            applyCustomTransforms(entity, partialTick, poseStack);

            Matrix4f[] palette = null;
            if (model.jointCount() > 0) {
                palette = model.newPalette();
                SkeletalAnimation clip = resolveAnimation(entity);
                float seconds = resolveAnimationTime(entity, partialTick);
                model.evaluatePose(clip, seconds, palette);
                applyProceduralBones(entity, model, palette, partialTick);
            }

            ModelSkinningRenderer.renderModel(
                    model,
                    palette,
                    poseStack,
                    bufferSource,
                    packedLight,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    0xFFFFFFFF,
                    resolveTextureOverrides(entity)
            );
        } finally {
            poseStack.popPose();
        }

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    protected void applyCustomTransforms(T entity, float partialTick, PoseStack poseStack) {
    }

    @Nullable
    protected SkeletalAnimation resolveAnimation(T entity) {
        return null;
    }

    protected float resolveAnimationTime(T entity, float partialTick) {
        return (entity.tickCount + partialTick) / 20.0F;
    }

    protected void applyProceduralBones(T entity, GltfModelAsset model, Matrix4f[] palette, float partialTick) {
    }

    @Nullable
    protected Map<ResourceLocation, ResourceLocation> resolveTextureOverrides(T entity) {
        return null;
    }
}
