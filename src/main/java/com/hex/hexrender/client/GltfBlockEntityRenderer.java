package com.hex.hexrender.client;

import com.hex.hexrender.direct.HexIrisBridge;
import com.hex.hexrender.gpu.GpuSkinningDispatcher;
import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.motion.SkeletalAnimation;
import com.hex.hexrender.render.ModelSkinningRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
        Minecraft mc = Minecraft.getInstance();
        boolean isShadowPass = HexIrisBridge.isShadowPassActive();

        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
            org.joml.Vector3f look = mc.gameRenderer.getMainCamera().getLookVector();
            BlockPos pos = blockEntity.getBlockPos();
            double dx = (pos.getX() + 0.5) - camPos.x;
            double dy = (pos.getY() + 0.5) - camPos.y;
            double dz = (pos.getZ() + 0.5) - camPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            // Projected distance along the player camera view vector
            double dot = dx * look.x + dy * look.y + dz * look.z;
            double radius = descriptor.boundingRadius(blockEntity);

            if (isShadowPass) {
                if (!descriptor.castsShadows(blockEntity)) {
                    return;
                }
                double maxDist = descriptor.shadowDistance(blockEntity);
                if (distSq > maxDist * maxDist) {
                    return;
                }
                // Behind-camera shadow culling: if block is > 16 blocks behind player,
                // its shadow cannot reach the camera's viewport - cull from shadow pass
                if (dot < -16.0 && distSq > 16.0 * 16.0) {
                    return;
                }
            } else {
                // Zero-latency Camera Plane Culling:
                // If the entire bounding sphere is behind the camera plane (dot < -radius),
                // it is mathematically impossible for the player to see it - cull immediately!
                if (dot < -radius && distSq > radius * radius) {
                    return;
                }
            }
        }

        GltfModelAsset model = isShadowPass ? descriptor.shadowModel(blockEntity) : null;
        if (model == null) {
            model = descriptor.model(blockEntity);
        }
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

            // Resolve lighting: only sample from above if the block is completely occluded (light == 0)
            int light = packedLight;
            if (light == 0 && blockEntity.getLevel() != null) {
                light = LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos().above());
            }

            int tint = descriptor.tint(blockEntity);

            GpuSkinningDispatcher.renderModel(
                    model,
                    palette,
                    poseStack,
                    bufferSource,
                    light,
                    packedOverlay,
                    tint,
                    descriptor.textureOverrides(blockEntity),
                    descriptor.enableVanillaPbr(blockEntity)
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            // Respect the player's full render distance: e.g. 16 chunks = 256 blocks, 32 chunks = 512 blocks
            return Math.max(512, mc.options.getEffectiveRenderDistance() * 16);
        }
        return 512;
    }
}
