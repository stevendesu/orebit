package com.orebit.mod.fabric;

import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;

/**
 * Subscribes to Fabric's command-registration event and forwards the dispatcher to the common code —
 * the one version-divergent Fabric command primitive (kept tiny; the command tree itself is common).
 *
 * <p>Baseline flavor (MC <b>1.17–1.18.2</b>): <b>fabric-command-api v1</b>, whose callback is
 * {@code (dispatcher, dedicated)}. MC 1.19 replaced this with v2's three-arg
 * {@code (dispatcher, registryAccess, environment)} in a new package, overridden by
 * {@code overlays-fabric/1.19}.
 */
final class FabricCommandRegistrar {

    private FabricCommandRegistrar() {}

    static void register(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> callback.accept(dispatcher));
    }
}
