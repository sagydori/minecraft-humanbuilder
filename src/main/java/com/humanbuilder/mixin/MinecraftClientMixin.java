package com.humanbuilder.mixin;

import com.humanbuilder.state.BuilderStateMachine;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the builder state machine from the end of the client tick, exactly as
 * mandated (Section 3, Module G runs on the client tick). We inject at TAIL so
 * that vanilla's {@code handleInputEvents()} for this tick has already run —
 * meaning any KeyBinding state we set here is consumed by vanilla on the *next*
 * tick, which is the natural one-tick input latency a real player exhibits.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void humanbuilder$onTick(CallbackInfo ci) {
        BuilderStateMachine.INSTANCE.onClientTick((MinecraftClient) (Object) this);
    }
}
