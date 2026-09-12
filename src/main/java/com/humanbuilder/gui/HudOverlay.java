package com.humanbuilder.gui;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.state.BuilderStateMachine;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Small top-left HUD overlay showing what the builder is doing: state,
 * throughput, remaining blocks and the whole-build ETA. Cleaner than spamming
 * the action bar (and it doesn't cover the hotbar item name).
 */
public final class HudOverlay {

    private HudOverlay() {}

    public static void register() {
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> render(ctx));
    }

    private static void render(DrawContext ctx) {
        if (!BuilderConfig.INSTANCE.showHud) return;
        if (!BuilderStateMachine.INSTANCE.isActive()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;

        List<String> lines = BuilderStateMachine.INSTANCE.statusLines();
        int x = 6, y = 6;
        for (String line : lines) {
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(line), x, y, 0xFFFFFFFF);
            y += 11;
        }
    }
}
