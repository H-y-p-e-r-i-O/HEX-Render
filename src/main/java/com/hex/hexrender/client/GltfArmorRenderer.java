package com.hex.hexrender.client;

import com.hex.hexrender.loader.GltfModelAsset;
import com.hex.hexrender.render.ModelSkinningRenderer;
import com.hex.hexrender.skeleton.RigHierarchy;
import com.hex.hexrender.skeleton.SkeletonPose;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.Map;

/**
 * Universal armor renderer for glTF 2.0 models, mapping vanilla entity limbs onto glTF skeleton joints.
 */
public class GltfArmorRenderer extends HumanoidModel<LivingEntity> {
    public static final Map<String, String> DEFAULT_BONE_MAPPING = Map.of(
            "head", "head",
            "body", "body",
            "left_arm", "left_arm",
            "right_arm", "right_arm",
            "left_leg", "left_leg",
            "right_leg", "right_leg"
    );

    private final ArmorModelDescriptor descriptor;
    private final Map<String, String> boneMapping;

    private final Quaternionf scratchRot = new Quaternionf();
    private final Quaternionf restRot = new Quaternionf();

    @Nullable
    private LivingEntity entity;
    @Nullable
    private ItemStack stack;
    @Nullable
    private EquipmentSlot slot;
    private boolean pending;

    public GltfArmorRenderer(ArmorModelDescriptor descriptor) {
        this(descriptor, DEFAULT_BONE_MAPPING);
    }

    public GltfArmorRenderer(ArmorModelDescriptor descriptor, Map<String, String> boneMapping) {
        super(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.descriptor = descriptor;
        this.boneMapping = Map.copyOf(boneMapping);
    }

    /**
     * Prepares this armor model for the upcoming entity draw call.
     */
    public GltfArmorRenderer prepare(@Nullable LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
        this.entity = entity;
        this.stack = stack;
        this.slot = slot;
        this.pending = true;
        return this;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int colour) {
        if (!pending || entity == null || stack == null || slot == null) {
            return;
        }
        pending = false;

        GltfModelAsset model = descriptor.model(entity, stack, slot);
        if (model == null) {
            return;
        }

        RigHierarchy rig = model.skeleton();
        SkeletonPose.NodeTransformState state = new SkeletonPose.NodeTransformState(rig.nodeCount());
        state.reset(rig);

        // Map vanilla limb rotations onto matching skeleton joints
        applyBone(rig, state, boneMapping.get("head"), head);
        applyBone(rig, state, boneMapping.get("body"), body);
        applyBone(rig, state, boneMapping.get("left_arm"), leftArm);
        applyBone(rig, state, boneMapping.get("right_arm"), rightArm);
        applyBone(rig, state, boneMapping.get("left_leg"), leftLeg);
        applyBone(rig, state, boneMapping.get("right_leg"), rightLeg);

        Matrix4f[] palette = model.newPalette();
        SkeletonPose.computeJointPalette(rig, state, palette);

        poseStack.pushPose();
        try {
            // Undo vanilla Y-down inverted scale (-1, -1, 1) to match standard glTF Y-up format
            poseStack.scale(-1.0f, -1.0f, 1.0f);

            int tint = descriptor.tint(entity, stack, slot);
            if (tint == 0xFFFFFFFF && colour != 0xFFFFFFFF) {
                tint = colour;
            }

            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            ModelSkinningRenderer.renderModel(
                    model,
                    palette,
                    poseStack,
                    bufferSource,
                    light,
                    overlay,
                    tint,
                    descriptor.textureOverrides(entity, stack, slot)
            );
        } finally {
            poseStack.popPose();
        }
    }

    private void applyBone(RigHierarchy rig, SkeletonPose.NodeTransformState state, @Nullable String boneName, ModelPart part) {
        if (boneName == null) {
            return;
        }
        int nodeIdx = rig.findNodeIndex(boneName);
        if (nodeIdx < 0) {
            return;
        }

        restRot.set(rig.restRotation(nodeIdx));
        scratchRot.set(restRot).mul(new Quaternionf().rotationZYX(part.zRot, -part.yRot, -part.xRot));
        state.rotations[nodeIdx].set(scratchRot);
    }
}
