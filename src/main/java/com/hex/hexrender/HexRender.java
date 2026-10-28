package com.hex.hexrender;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(HexRender.MOD_ID)
public final class HexRender {
    public static final String MOD_ID = "hexrender";

    public static final Logger LOGGER = LoggerFactory.getLogger("HEX Render");

    public HexRender(IEventBus modBus, Dist dist) {
        LOGGER.info("HEX Render loading");
        if (dist.isClient()) {
            modBus.addListener(com.hex.hexrender.core.ModelRegistry::onRegisterReloadListeners);
            modBus.addListener(com.hex.hexrender.render.HexRenderTypes::onRegisterShaders);
        }
    }
}
