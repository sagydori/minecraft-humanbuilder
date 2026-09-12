package com.humanbuilder.mixin;

import com.humanbuilder.state.BuilderStateMachine;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the bot is active we swallow real hardware mouse input so the user
 * cannot fight the aim controller or fire stray clicks that desync placement.
 * The toggle key is a keyboard binding (see {@code HumanBuilderClient}), so it
 * is unaffected; and the bot drives placement through KeyBinding state, not
 * through {@link Mouse}, so its own "clicks" are never blocked here.
 */
@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void humanbuilder$blockButtons(long window, int button, int action, int mods, CallbackInfo ci) {
        if (BuilderStateMachine.INSTANCE.isControllingInput()) {
            ci.cancel();
        }
    }

    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void humanbuilder$blockLook(long window, double x, double y, CallbackInfo ci) {
        if (BuilderStateMachine.INSTANCE.isControllingInput()) {
            ci.cancel();
        }
    }
}
