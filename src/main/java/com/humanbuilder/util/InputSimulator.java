package com.humanbuilder.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * Hardware-equivalent input: drives the vanilla {@link KeyBinding} state exactly
 * as the physical key would, per the mandate
 * ({@code setKeyPressed(true) -> onKeyPressed() -> ... -> setKeyPressed(false)}).
 * Vanilla's {@code handleInputEvents}/{@code doItemUse} then run natively on the
 * next tick; no packet is ever constructed here.
 */
public final class InputSimulator {

    private InputSimulator() {}

    public static void setUse(MinecraftClient client, boolean pressed) {
        set(client.options.useKey, pressed);
    }

    public static void setSneak(MinecraftClient client, boolean pressed) {
        set(client.options.sneakKey, pressed);
    }

    public static void releaseAll(MinecraftClient client) {
        set(client.options.useKey, false);
        set(client.options.sneakKey, false);
    }

    private static void set(KeyBinding binding, boolean pressed) {
        InputUtil.Key key = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
        KeyBinding.setKeyPressed(key, pressed);
        if (pressed) {
            // Registers a fresh "press" so vanilla's wasPressed()/press-count sees it.
            KeyBinding.onKeyPressed(key);
        }
    }
}
