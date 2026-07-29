package com.orebit.mod.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.CraftingOps;

import net.minecraft.server.MinecraftServer;

/**
 * The baked result-item → {@link KnownRecipe} index (DESIGN-bot-abilities.md §3.3) — the
 * {@code /bot craft} name table and recipe source. Baked once at SERVER_STARTED (the
 * {@code MiningModel.buildTable} pattern): vanilla recipes are DATAPACK-loaded per server, so they
 * exist neither at static-init nor under a bare registry bootstrap — recipe-dependent logic can
 * only be exercised against a live server (unit tests use synthetic {@link KnownRecipe}s instead).
 *
 * <p>Enumeration goes through the {@code platform/CraftingOps} seam anchored on
 * {@code MinecraftServer#getRecipeManager()} (byte-stable 1.17.1→26.2). Only shaped + shapeless
 * crafting recipes are indexed (§10-D5). Recipes are sorted by id so every downstream tiebreak is
 * deterministic.
 *
 * <p><b>Known limitation:</b> a datapack {@code /reload} that changes recipes is not re-baked until
 * the next server start (or {@code /bot config reload}, which re-bakes as a courtesy).
 */
public final class RecipeIndex {

    private RecipeIndex() {}

    /** Result NAME (item id path, the {@code /bot craft} token) → its recipes, id-sorted. */
    private static Map<String, List<KnownRecipe>> byResultName = Map.of();
    /** All craftable result names, sorted — the command's tab-completion list. */
    private static List<String> names = List.of();

    /** Whether {@link #bake} has run (recipes are only available on a started server). */
    public static boolean ready() {
        return !byResultName.isEmpty();
    }

    /** Enumerate the server's crafting recipes and (re)build the index. Cold; runs at SERVER_STARTED. */
    public static void bake(MinecraftServer server) {
        final List<KnownRecipe> all = new ArrayList<>(CraftingOps.listCrafting(server));
        all.sort((a, b) -> a.id().compareTo(b.id()));
        final Map<String, List<KnownRecipe>> byName = new HashMap<>();
        for (KnownRecipe r : all) {
            byName.computeIfAbsent(r.resultName(), k -> new ArrayList<>()).add(r);
        }
        final List<String> sortedNames = new ArrayList<>(byName.keySet());
        Collections.sort(sortedNames);
        byResultName = byName;
        names = Collections.unmodifiableList(sortedNames);
        OrebitCommon.LOGGER.info("[Orebit] recipe index: {} craftable results from {} recipes",
                sortedNames.size(), all.size());
    }

    /** The recipes producing result name {@code name} (id-sorted), or an empty list. */
    public static List<KnownRecipe> forName(String name) {
        return byResultName.getOrDefault(name, List.of());
    }

    /** All craftable result names, sorted — the {@code /bot craft} suggestion list. */
    public static List<String> names() {
        return names;
    }
}
