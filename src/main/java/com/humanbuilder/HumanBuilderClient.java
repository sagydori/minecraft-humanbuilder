package com.humanbuilder;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.gui.BuilderScreen;
import com.humanbuilder.gui.HudOverlay;
import com.humanbuilder.nav.PathRenderer;
import com.humanbuilder.state.BuilderStateMachine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HumanBuilderClient implements ClientModInitializer {

    public static final String MOD_ID = "humanbuilder";
    public static final Logger LOGGER = LoggerFactory.getLogger("HumanBuilder");

    private KeyBinding toggleKey;
    private KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        BuilderConfig.load();

        // Since 1.21.9 the keybind category is a KeyBinding.Category keyed by an
        // Identifier, not a translation-key string.
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.humanbuilder.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                category
        ));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.humanbuilder.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                category
        ));

        // The state machine itself runs from MinecraftClientMixin#tick (TAIL),
        // as mandated. Here we only watch the keys and reset on disconnect.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) {
                if (client.player != null && client.currentScreen == null) {
                    client.setScreen(new BuilderScreen());
                }
            }
            while (toggleKey.wasPressed()) {
                boolean now = !BuilderConfig.INSTANCE.enabled;
                BuilderConfig.INSTANCE.enabled = now;
                BuilderStateMachine.INSTANCE.reset();
                message(client, now ? "§aHumanBuilder enabled" : "§cHumanBuilder disabled");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            BuilderConfig.INSTANCE.enabled = false;
            BuilderConfig.INSTANCE.buildBounds = null;
            BuilderConfig.INSTANCE.selectedSchematic = null;
            BuilderStateMachine.INSTANCE.reset();
        });

        // Red path line + HUD overlay.
        PathRenderer.register();
        HudOverlay.register();

        LOGGER.info("HumanBuilder initialized. Toggle with the configured key (default: B).");
    }

    private static void message(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), true);
        }
    }
}
