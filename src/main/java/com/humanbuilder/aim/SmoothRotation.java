package com.humanbuilder.aim;

/**
 * Implemented by {@code ClientPlayerEntity} via mixin so the aim controller can
 * apply rotation and collapse render interpolation on demand.
 */
public interface SmoothRotation {
    void humanbuilder$setRotation(float yaw, float pitch);
    void humanbuilder$syncPrevRotation();
}
