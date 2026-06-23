package com.orebit.mod.fabric;

import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

/**
 * Subscribes to Fabric's command-registration event and forwards the dispatcher to the common code.
 *
 * <p>Flavor for MC <b>1.19+</b>: <b>fabric-command-api v2</b> (new package), whose callback gained the
 * registry-access + environment parameters — {@code (dispatcher, registryAccess, environment)}. We only
 * need the dispatcher. Overrides the {@code overlays-fabric/1.17} v1 baseline.
 */
final class FabricCommandRegistrar {

    private FabricCommandRegistrar() {}

    static void register(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> callback.accept(dispatcher));
    }
}
