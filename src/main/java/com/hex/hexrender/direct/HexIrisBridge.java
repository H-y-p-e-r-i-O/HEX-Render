package com.hex.hexrender.direct;

import java.lang.reflect.Method;

/**
 * Reflective bridge to query Iris/Oculus shaderpack and shadow pass status.
 */
public final class HexIrisBridge {
    private static boolean initialized;
    private static boolean available;

    private static Object irisApiInstance;
    private static Method isShaderPackInUseMethod;
    private static Method isRenderingShadowPassMethod;
    private static Method areShadowsCurrentlyBeingRenderedMethod;

    private HexIrisBridge() {
    }

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstance = irisApiClass.getMethod("getInstance");
            irisApiInstance = getInstance.invoke(null);
            isShaderPackInUseMethod = irisApiClass.getMethod("isShaderPackInUse");

            try {
                isRenderingShadowPassMethod = irisApiClass.getMethod("isRenderingShadowPass");
            } catch (Throwable ignored) {
            }

            try {
                Class<?> shadowStateClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
                areShadowsCurrentlyBeingRenderedMethod = shadowStateClass.getMethod("areShadowsCurrentlyBeingRendered");
            } catch (Throwable ignored) {
            }

            available = true;
        } catch (Throwable t) {
            available = false;
        }
    }

    /**
     * Checks whether an Iris shaderpack is currently active and rendering.
     */
    public static boolean hasActiveShaders() {
        init();
        if (!available || irisApiInstance == null || isShaderPackInUseMethod == null) {
            return false;
        }
        try {
            return (boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
        } catch (Throwable t) {
            return false;
        }
    }

    @Deprecated
    public static boolean isShaderPackInUse() {
        return hasActiveShaders();
    }

    /**
     * Checks whether Iris is currently executing the shadow map pass.
     */
    public static boolean isShadowPassActive() {
        init();
        if (!available) {
            return false;
        }
        if (irisApiInstance != null && isRenderingShadowPassMethod != null) {
            try {
                return (boolean) isRenderingShadowPassMethod.invoke(irisApiInstance);
            } catch (Throwable ignored) {
            }
        }
        if (areShadowsCurrentlyBeingRenderedMethod != null) {
            try {
                return (boolean) areShadowsCurrentlyBeingRenderedMethod.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Deprecated
    public static boolean isRenderingShadows() {
        return isShadowPassActive();
    }
}
