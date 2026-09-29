package net.camacraft.grappleunbound.common;

import net.camacraft.grappleunbound.GrappleMod;
import net.camacraft.grappleunbound.config.GrappleConfig;
import net.camacraft.grappleunbound.entities.grapplehook.GrapplehookEntity;
import net.camacraft.grappleunbound.items.GrapplehookItem;
import net.camacraft.grappleunbound.items.LongFallBoots;
import net.camacraft.grappleunbound.network.GrappleDetachMessage;
import net.camacraft.grappleunbound.network.LoggedInMessage;
import net.camacraft.grappleunbound.server.ServerControllerManager;
import net.camacraft.grappleunbound.utils.GrapplemodUtils;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.event.level.BlockEvent.BreakEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;

public class CommonEventHandlers {
	public CommonEventHandlers() {
	    MinecraftForge.EVENT_BUS.register(this);

		// config/grapplemod.toml (the id before Grapple Unbound) becomes config/grappleunbound.toml
		LegacyIds.migrateConfigFile();
		AutoConfig.register(GrappleConfig.class, Toml4jConfigSerializer<GrappleConfig>::new);
	}

	@SubscribeEvent
    public void onBlockBreak(BreakEvent event) {
    	Player player = event.getPlayer();
    	if (player != null) {
	    	ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
	    	if (stack != null) {
	    		Item item = stack.getItem();
	    		if (item instanceof GrapplehookItem) {
	    			event.setCanceled(true);
	    			return;
	    		}
	    	}
    	}
    }
    
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
    	if (!event.getEntity().level().isClientSide) {
    		detachAll(event.getEntity());
    	}
	}

	/**
	 * Server side: lets go of every hook this entity has out and tells its client, the way a death
	 * does. Also run on a teleport, a game mode change and a dimension change, none of which a
	 * rope should survive (a hook left behind otherwise yanks the player back, or holds on until
	 * it notices the distance).
	 */
	public static void detachAll(Entity entity) {
		int id = entity.getId();
		boolean isconnected = ServerControllerManager.allGrapplehookEntities.containsKey(id);
		if (isconnected) {
			HashSet<GrapplehookEntity> grapplehookEntities = ServerControllerManager.allGrapplehookEntities.get(id);
			for (GrapplehookEntity hookEntity: grapplehookEntities) {
				hookEntity.removeServer();
			}
			grapplehookEntities.clear();

			ServerControllerManager.attached.remove(id);

			if (GrapplehookItem.grapplehookEntitiesLeft.containsKey(entity)) {
				GrapplehookItem.grapplehookEntitiesLeft.remove(entity);
			}
			if (GrapplehookItem.grapplehookEntitiesRight.containsKey(entity)) {
				GrapplehookItem.grapplehookEntitiesRight.remove(entity);
			}

			GrapplemodUtils.sendToCorrectClient(new GrappleDetachMessage(id), id, entity.level());
		}
	}

	// LOWEST: a teleport or mode change another mod cancels leaves the rope alone
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onEntityTeleport(EntityTeleportEvent event) {
		if (!event.getEntity().level().isClientSide) {
			detachAll(event.getEntity());
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
		if (!event.getEntity().level().isClientSide && event.getNewGameMode() != event.getCurrentGameMode()) {
			detachAll(event.getEntity());
		}
	}

	@SubscribeEvent
	public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
		if (!event.getEntity().level().isClientSide) {
			detachAll(event.getEntity());
		}
	}

	/**
	 * Long Fall Boots: no fall damage, and no vanilla fly-into-wall damage (what the boots always
	 * blocked). Falls are tested by tag, never by exact source: Full Stop replaces vanilla's fall
	 * damage with fullstop:kinetic_fall, which is in #minecraft:is_fall (as is WallJump's wall-cling
	 * catch). Slamming into a wall is NOT covered: Full Stop's fullstop:kinetic wall and crash damage
	 * (e.g. a grapple swing or motor pull into a wall) still hurts in the boots.
	 */
	@SubscribeEvent
	public void onLivingAttack(LivingAttackEvent event) {
		if (event.getEntity() instanceof Player player && isFallDamage(event.getSource())) {
			for (ItemStack armor : player.getArmorSlots()) {
				if (armor != null && armor.getItem() instanceof LongFallBoots) {
					event.setCanceled(true);
					return;
				}
			}
		}
	}

	private static boolean isFallDamage(DamageSource source) {
		return source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypes.FLY_INTO_WALL);
	}
	
	@SubscribeEvent
	public void onLivingFallEvent(LivingFallEvent event) {
		if (event.getEntity() != null && event.getEntity() instanceof Player) {
			Player player = (Player)event.getEntity();
			
			for (ItemStack armor : player.getArmorSlots()) {
			    if (armor != null && armor.getItem() instanceof LongFallBoots)
			    {
					// this cancels the fall event so you take no damage
					event.setCanceled(true);
			    }
			}
		}
	}

	@SubscribeEvent
	public void onServerStart(ServerStartedEvent event) {
		if (GrappleConfig.getConf().other.override_allowflight) {
			event.getServer().setFlightAllowed(true);
		}
	}
	
	@SubscribeEvent
	public void onPlayerLoggedInEvent(PlayerLoggedInEvent e) {
		if (e.getEntity() instanceof ServerPlayer) {
			CommonSetup.network.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) e.getEntity()), new LoggedInMessage(GrappleConfig.getConf()));
		} else {
			GrappleMod.LOGGER.warn("Logged-in entity is not a ServerPlayer");
		}
	}

	// the static hook/controller maps are keyed by entity/entity-id; clean them up on logout
	// so they don't leak player and hook references across sessions
	@SubscribeEvent
	public void onPlayerLoggedOutEvent(PlayerLoggedOutEvent e) {
		if (!(e.getEntity() instanceof ServerPlayer player)) {
			return;
		}
		int id = player.getId();
		ServerControllerManager.attached.remove(id);
		HashSet<GrapplehookEntity> hooks = ServerControllerManager.allGrapplehookEntities.remove(id);
		if (hooks != null) {
			for (GrapplehookEntity hookEntity : hooks) {
				if (hookEntity != null && hookEntity.isAlive()) {
					hookEntity.removeServer();
				}
			}
		}
		GrapplehookItem.grapplehookEntitiesLeft.remove(player);
		GrapplehookItem.grapplehookEntitiesRight.remove(player);
	}
}
