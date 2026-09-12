package com.humanbuilder.mixin;

import com.humanbuilder.aim.SmoothRotation;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Rotation injection surface. The aim controller applies small per-tick yaw/pitch
 * deltas via {@link Entity#setYaw}/{@link Entity#setPitch}, which the renderer
 * interpolates smoothly on its own. The one case that would otherwise streak the
 * camera is the instantaneous "snap back" after an overshoot correction — there
 * we call {@link #humanbuilder$syncPrevRotation()} so the render-interpolated
 * previous angle is collapsed onto the current angle for that frame.
 *
 * {@code prevYaw}/{@code prevPitch} are inherited public fields of {@link Entity}
 * and are shadowed here for direct, allocation-free access.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin implements SmoothRotation {

    @Shadow public float prevYaw;
    @Shadow public float prevPitch;

    @Override
    public void humanbuilder$setRotation(float yaw, float pitch) {
        Entity self = (Entity) (Object) this;
        self.setYaw(yaw);
        self.setPitch(pitch);
    }

    @Override
    public void humanbuilder$syncPrevRotation() {
        Entity self = (Entity) (Object) this;
        this.prevYaw = self.getYaw();
        this.prevPitch = self.getPitch();
    }
}
