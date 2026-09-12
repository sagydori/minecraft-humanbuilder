package com.humanbuilder.nav;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.state.BuilderStateMachine;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws the active navigation path as a red line in the world so you can see
 * where the builder is walking.
 */
public final class PathRenderer {

    private PathRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PathRenderer::onRender);
    }

    private static void onRender(WorldRenderContext ctx) {
        if (!BuilderConfig.INSTANCE.showPath) return;
        if (!BuilderStateMachine.INSTANCE.isControllingInput()) return;

        List<Vec3d> pts = NavigationController.INSTANCE.getRenderPath();
        if (pts.size() < 2) return;

        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        if (ms == null || consumers == null || ctx.camera() == null) return;

        Vec3d cam = ctx.camera().getPos();
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = ms.peek();
        Matrix4f mat = entry.getPositionMatrix();

        for (int i = 0; i < pts.size() - 1; i++) {
            Vec3d a = pts.get(i).subtract(cam);
            Vec3d b = pts.get(i + 1).subtract(cam);
            float nx = (float) (b.x - a.x), ny = (float) (b.y - a.y), nz = (float) (b.z - a.z);
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0e-4f) continue;
            nx /= len; ny /= len; nz /= len;

            vc.vertex(mat, (float) a.x, (float) a.y, (float) a.z).color(255, 30, 30, 255).normal(entry, nx, ny, nz);
            vc.vertex(mat, (float) b.x, (float) b.y, (float) b.z).color(255, 30, 30, 255).normal(entry, nx, ny, nz);
        }
    }
}
