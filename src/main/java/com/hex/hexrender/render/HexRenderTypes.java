package com.hex.hexrender.render;

import com.hex.hexrender.HexRender;
import com.hex.hexrender.direct.HexIrisBridge;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom PBR render types providing real-time specular highlights (блики) on weapons, grenades, and tools.
 */
public final class HexRenderTypes {
    public static @Nullable ShaderInstance PBR_SHADER = null;

    private static final ConcurrentHashMap<ResourceLocation, RenderType> PBR_RENDER_TYPES = new ConcurrentHashMap<>();

    private HexRenderTypes() {
    }

    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(HexRender.MOD_ID, "rendertype_entity_pbr"),
                            DefaultVertexFormat.NEW_ENTITY
                    ),
                    shader -> {
                        PBR_SHADER = shader;
                        PBR_RENDER_TYPES.clear();
                        HexRender.LOGGER.info("Registered HEX Render PBR core shader successfully");
                    }
            );
        } catch (IOException e) {
            HexRender.LOGGER.error("Failed to load HEX Render PBR shader", e);
        }
    }

    public static RenderType entityPbr(ResourceLocation texture) {
        if (HexIrisBridge.hasActiveShaders()) {
            return RenderType.entityCutoutNoCull(texture);
        }
        if (PBR_SHADER != null) {
            return PBR_RENDER_TYPES.computeIfAbsent(texture, HexRenderTypes::createEntityPbr);
        }
        return RenderType.entityCutoutNoCull(texture);
    }

    private static RenderType createEntityPbr(ResourceLocation texture) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(() -> PBR_SHADER))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(true);

        return RenderType.create(
                "hexrender_entity_pbr",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                true,
                false,
                state
        );
    }
}
