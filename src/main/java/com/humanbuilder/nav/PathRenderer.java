package com.humanbuilder.nav;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.state.BuilderStateMachine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side red line showing where the builder is going: it traces the active
 * navigation path, and — whenever there's a current target — a straight line from
 * the player to the block being built/walked to. Drawn as a dense, fine trail of
 * red redstone-dust particles (the 1.21.9+ render rework removed the simple
 * in-world line API; a particle line is the version-stable, no-logic-impact way).
 */
public final class PathRenderer {

    private static final int RED = 0xFF1818;   // bright red
    private static final float SCALE = 0.6f;   // small dust = fine line
    private static final double SPACING = 0.28; // distance between dots along the line
    private static final int MAX_POINTS = 220;  // per-frame budget

    private PathRenderer() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PathRenderer::onTick);
    }

    private static void onTick(MinecraftClient client) {
        if (!BuilderConfig.INSTANCE.showPath) return;
        if (!BuilderStateMachine.INSTANCE.isActive() || BuilderStateMachine.INSTANCE.isPaused()) return;
        if (client.world == null || client.player == null || client.particleManager == null) return;

        List<Vec3d> route = new ArrayList<>();

        // Prefer the actual walking path; otherwise draw straight to the target.
        List<Vec3d> nav = NavigationController.INSTANCE.getRenderPath();
        if (nav.size() >= 2) {
            route.addAll(nav);
        } else {
            Vec3d target = BuilderStateMachine.INSTANCE.getActiveTargetCenter();
            if (target != null) {
                route.add(client.player.getEyePos());
                route.add(target);
            }
        }
        if (route.size() < 2) return;

        DustParticleEffect dust = new DustParticleEffect(RED, SCALE);
        int budget = MAX_POINTS;
        for (int i = 0; i < route.size() - 1 && budget > 0; i++) {
            Vec3d a = route.get(i);
            Vec3d b = route.get(i + 1);
            double dist = a.distanceTo(b);
            int steps = Math.max(1, (int) (dist / SPACING));
            for (int s = 0; s <= steps && budget > 0; s++) {
                double t = (double) s / steps;
                client.particleManager.addParticle(dust,
                        a.x + (b.x - a.x) * t,
                        a.y + (b.y - a.y) * t,
                        a.z + (b.z - a.z) * t,
                        0.0, 0.0, 0.0);
                budget--;
            }
        }
    }
}
