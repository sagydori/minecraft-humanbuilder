package com.humanbuilder.nav;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.state.BuilderStateMachine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Marks the active navigation path in red so you can see where the builder is
 * walking. Implemented with redstone-dust particles along the path rather than
 * custom immediate-mode line rendering — the particle API is stable across the
 * 1.21.9+ render-pipeline rework, whereas {@code RenderLayer.getLines()} / vertex
 * consumers are not.
 */
public final class PathRenderer {

    /** Packed RGB red. */
    private static final int RED = 0xFF3020;

    private static int tickCounter;

    private PathRenderer() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PathRenderer::onTick);
    }

    private static void onTick(MinecraftClient client) {
        if (!BuilderConfig.INSTANCE.showPath) return;
        if (!BuilderStateMachine.INSTANCE.isControllingInput()) return;
        if (client.world == null || client.player == null) return;
        if ((tickCounter++ & 1) != 0) return; // spawn every other tick

        List<Vec3d> pts = NavigationController.INSTANCE.getRenderPath();
        if (pts.size() < 2) return;

        DustParticleEffect dust = new DustParticleEffect(RED, 1.0f);
        for (int i = 0; i < pts.size() - 1; i++) {
            Vec3d a = pts.get(i);
            Vec3d b = pts.get(i + 1);
            double dist = a.distanceTo(b);
            int steps = Math.max(1, (int) (dist / 0.4));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                // (effect, alwaysSpawn, canSpawnOnMinimal, x, y, z, vx, vy, vz)
                client.world.addParticle(dust, true, false,
                        a.x + (b.x - a.x) * t,
                        a.y + (b.y - a.y) * t,
                        a.z + (b.z - a.z) * t,
                        0.0, 0.0, 0.0);
            }
        }
    }
}
