package net.camacraft.grappleunbound;

import net.camacraft.grappleunbound.common.CommonEventHandlers;
import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.common.LegacyIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 * This file is part of GrappleMod.

    GrappleMod is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GrappleMod is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with GrappleMod.  If not, see <http://www.gnu.org/licenses/>.
 */

//TODO
// Pull mobs
// Attach 2 things together
// wallrun on diagonal walls
// smart motor acts erratically when aiming above hook
// key events

@Mod(GrappleMod.MODID)
public class GrappleMod {
    public static final String MODID = "grappleunbound";

    public static final Logger LOGGER = LogManager.getLogger();

    // Camafy physics ownership (see PhysicsOwnership): the rope, the forcefield and the air
    // controller's wall-run/slide/jumps claim the player's MOTION under this name, so other
    // movement mods (WallJump Unbound) stand back. MOTION only, never IMPACT or G_FORCE: grappling
    // is meant to be dangerous, so Full Stop's wall-slam damage and g-force still apply
    public static final ResourceLocation PHYSICS_OWNER = new ResourceLocation(MODID, "grapple");

    public GrappleMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        CommonSetup.BLOCKS.register(bus);
        CommonSetup.ITEMS.register(bus);
        CommonSetup.ENTITY_TYPES.register(bus);
        CommonSetup.ENCHANTMENTS.register(bus);
        CommonSetup.BLOCK_ENTITY_TYPES.register(bus);
        CommonSetup.TABS.register(bus);
        CommonSetup.RECIPE_SERIALIZERS.register(bus);

        // explicit construction instead of a static-initializer side effect: registers the
        // forge-bus event handlers and the AutoConfig serializer
        CommonSetup.eventHandlers = new CommonEventHandlers();

        // worlds saved before the rename keep their hooks, blocks and enchantments
        MinecraftForge.EVENT_BUS.addListener(LegacyIds::onMissingMappings);
    }
}
