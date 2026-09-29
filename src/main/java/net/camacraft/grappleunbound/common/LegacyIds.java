package net.camacraft.grappleunbound.common;

import net.camacraft.grappleunbound.GrappleMod;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegistryManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Carries worlds and configs over from the mod's id before it became Grapple Unbound: GrappleVS
 * kept the {@code grapplemod} id of yyon's Grappling Hook Mod.
 *
 * <p>Every registry entry a world saved under {@code grapplemod:<path>} (items, the modifier
 * block and its block entity, the hook entity, the three enchantments, the upgrade recipe
 * serializer, the creative tab, and anything added later) is remapped to
 * {@code grappleunbound:<path>} when the world loads. Forge then aliases the old id to the new one
 * for the session, so items in chests and inventories, enchantments on boots, placed blocks and
 * block entities saved with the old ids all load as the new ones, and are saved with the new ids
 * from then on.
 */
public final class LegacyIds {

    /** The mod id before the rename. */
    public static final String OLD_NAMESPACE = "grapplemod";

    /** Registry paths that changed with the rename; every other entry kept its path. */
    private static final Map<String, String> RENAMED_PATHS = Map.of(
            "grapplemod", "main"); // the creative tab: grapplemod:grapplemod -> grappleunbound:main

    private LegacyIds() {
    }

    /** Forge bus: remaps a loading world's {@code grapplemod:*} entries to this mod's. */
    public static void onMissingMappings(MissingMappingsEvent event) {
        remap(event);
    }

    private static <T> void remap(MissingMappingsEvent event) {
        @SuppressWarnings("unchecked")
        ResourceKey<? extends Registry<T>> key = (ResourceKey<? extends Registry<T>>) (ResourceKey<?>) event.getKey();
        List<MissingMappingsEvent.Mapping<T>> mappings = event.getMappings(key, OLD_NAMESPACE);
        if (mappings.isEmpty()) {
            return;
        }
        IForgeRegistry<T> registry = RegistryManager.ACTIVE.getRegistry(key);
        int remapped = 0;
        for (MissingMappingsEvent.Mapping<T> mapping : mappings) {
            String path = mapping.getKey().getPath();
            ResourceLocation newId = new ResourceLocation(GrappleMod.MODID, RENAMED_PATHS.getOrDefault(path, path));
            if (registry != null && registry.containsKey(newId)) {
                mapping.remap(registry.getValue(newId));
                remapped++;
            } else {
                GrappleMod.LOGGER.warn("Nothing to carry {} over to: {} has no {}", mapping.getKey(), key.location(), newId);
            }
        }
        GrappleMod.LOGGER.info("Carried {} {} entr{} over from {} to {}", remapped, key.location(),
                remapped == 1 ? "y" : "ies", OLD_NAMESPACE, GrappleMod.MODID);
    }

    /**
     * Moves the old config file ({@code config/grapplemod.toml}) to this mod's name, once: only
     * when there is an old file and no new one yet. Call before the config is registered.
     */
    public static void migrateConfigFile() {
        Path dir = FMLPaths.CONFIGDIR.get();
        Path oldFile = dir.resolve(OLD_NAMESPACE + ".toml");
        Path newFile = dir.resolve(GrappleMod.MODID + ".toml");
        if (!Files.exists(oldFile) || Files.exists(newFile)) {
            return;
        }
        try {
            Files.move(oldFile, newFile);
            GrappleMod.LOGGER.info("Moved the old config {} to {}", oldFile.getFileName(), newFile.getFileName());
        } catch (IOException e) {
            GrappleMod.LOGGER.warn("Could not move the old config {} to {} (starting from defaults): {}",
                    oldFile, newFile, e.toString());
        }
    }
}
