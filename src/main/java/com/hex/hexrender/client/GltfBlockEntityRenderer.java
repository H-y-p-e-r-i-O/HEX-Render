package com.hex.hexrender.client;

import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.hex.hexrender.render.ModelSkinningRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Universal block entity and multiblock renderer for glTF 2.0 models.
 *
 * @param <T> The BlockEntity type
 */
public class GltfBlockEntityRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {

    protected final BlockEntityRendererProvider.Context context;
    protected final BlockEntityModelDescriptor<T> descriptor;

    public GltfBlockEntityRenderer(BlockEntityRendererProvider.Context context, BlockEntityModelDescriptor<T> descriptor) {
        this.context = context;
        this.descriptor = descriptor;
    }

    public BlockEntityModelDescriptor<T> descriptor() {
        return descriptor;
    }

    @Override
    public void render(
            T blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        GltfModelAsset model = descriptor.model(blockEntity);
        if (model == null) {
            return;
        }

        poseStack.pushPose();
        try {
            // Center in block space (0.5, 0.0, 0.5)
            poseStack.translate(0.5f, 0.0f, 0.5f);

            // Apply horizontal facing rotation if present
            BlockState state = blockEntity.getBlockState();
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            } else if (state.hasProperty(BlockStateProperties.FACING)) {
                Direction facing = state.getValue(BlockStateProperties.FACING);
                if (facing.getAxis().isHorizontal()) {
                    poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
                }
            }

            // Custom descriptor transforms
            descriptor.transform(blockEntity, partialTick, poseStack);

            // Skeletal pose evaluation
            Matrix4f[] palette = null;
            if (model.jointCount() > 0) {
                palette = model.newPalette();
                SkeletalAnimation clip = descriptor.clip(blockEntity);
                float seconds = descriptor.seconds(blockEntity, partialTick);
                model.evaluatePose(clip, seconds, palette);
            }

            // Resolve lighting: sample from above the block if inside a solid/occluded structure
            int light = packedLight;
            if (blockEntity.getLevel() != null) {
                int lightAbove = LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos().above());
                light = Math.max(light, lightAbove);
            }

            int tint = descriptor.tint(blockEntity);

            ModelSkinningRenderer.renderModel(
                    model,
                    palette,
                    poseStack,
                    bufferSource,
                    light,
                    packedOverlay,
                    tint,
                    descriptor.textureOverrides(blockEntity)
            );
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public AABB getRenderBoundingBox(T blockEntity) {
        return descriptor.renderBoundingBox(blockEntity);
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
