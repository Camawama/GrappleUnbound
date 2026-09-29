package net.camacraft.grappleunbound.client;

import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.config.GrappleConfig;
import net.camacraft.grappleunbound.controllers.AirfrictionController;
import net.camacraft.grappleunbound.controllers.ForcefieldController;
import net.camacraft.grappleunbound.controllers.GrappleController;
import net.camacraft.grappleunbound.items.KeypressItem;
import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.minecraftforge.client.event.InputEvent.Key;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent.BreakEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// registered via the constructor (called from ClientSetup); an @EventBusSubscriber annotation
// would only pick up static handlers and all handlers here are instance methods
public class ClientEventHandlers {
	public static ClientEventHandlers instance = null;

	public ClientEventHandlers() {
		MinecraftForge.EVENT_BUS.register(this);
	}

	private static class HeldKey {
		private final KeyMapping mapping;
		private final KeypressItem.Keys key;
		private boolean held;

		private HeldKey(KeyMapping mapping, KeypressItem.Keys key) {
			this.mapping = mapping;
			this.key = key;
			this.held = false;
		}
	}

	private final HeldKey[] keyStates = {
			new HeldKey(ClientSetup.key_enderlaunch, KeypressItem.Keys.LAUNCHER),
			new HeldKey(ClientSetup.key_leftthrow, KeypressItem.Keys.THROWLEFT),
			new HeldKey(ClientSetup.key_rightthrow, KeypressItem.Keys.THROWRIGHT),
			new HeldKey(ClientSetup.key_boththrow, KeypressItem.Keys.THROWBOTH),
			new HeldKey(ClientSetup.key_rocket, KeypressItem.Keys.ROCKET)
	};

	/**
	 * Once per tick. This used to run on both phases of the event, i.e. the rope physics stepped
	 * twice a tick and the START pass wrote over anything another mod had done to the player's
	 * velocity since the END pass. Now the controllers step at START only (taking the two steps
	 * they were tuned for back to back, see GrappleController.STEPS_PER_TICK): the same steps in
	 * the same order around the player's move, so the feel and the key response are unchanged.
	 * END only notes the velocity the move left, for the next START to compare.
	 */
	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		Player player = Minecraft.getInstance().player;
		if (player != null) {
			if (!Minecraft.getInstance().isPaused()) {
				if (event.phase == TickEvent.Phase.END) {
					ClientControllerManager.instance.afterMove(player);
					return;
				}

				ClientControllerManager.instance.onClientTick(player);

				if (Minecraft.getInstance().screen == null) {
					// in-hand hook customization screen
					while (ClientSetup.key_hookconfig.consumeClick()) {
						InteractionHand hand = InteractionHand.MAIN_HAND;
						ItemStack held = player.getMainHandItem();
						if (!(held.getItem() instanceof net.camacraft.grappleunbound.items.GrapplehookItem)) {
							hand = InteractionHand.OFF_HAND;
							held = player.getOffhandItem();
						}
						if (held.getItem() instanceof net.camacraft.grappleunbound.items.GrapplehookItem) {
							Minecraft.getInstance().setScreen(new net.camacraft.grappleunbound.blocks.modifierblock.GuiModifier(hand, held));
						}
					}

					for (HeldKey keyState : keyStates) {
						while (keyState.mapping.consumeClick()) {
							keyState.held = true;
							triggerKeyDown(player, keyState);
						}

						if (keyState.held && !keyState.mapping.isDown()) {
							triggerKeyUp(player, keyState);
							keyState.held = false;
						}
					}
				}
			}
		}
	}

	private void triggerKeyDown(Player player, HeldKey keyState) {
		ItemStack stack = getKeypressStack(player);
		if (stack != null && !isLookingAtModifierBlock(player)) {
			((KeypressItem) stack.getItem()).onCustomKeyDown(stack, player, keyState.key, true);
		}
	}

	private void triggerKeyUp(Player player, HeldKey keyState) {
		ItemStack stack = getKeypressStack(player);
		if (stack != null && !isLookingAtModifierBlock(player)) {
			((KeypressItem) stack.getItem()).onCustomKeyUp(stack, player, keyState.key, true);
		}
	}

	@SubscribeEvent
	public void onBlockBreak(BreakEvent event) {
		if (event.getPos() != null) {
			if (ClientControllerManager.controllerPos.containsKey(event.getPos())) {
				GrappleController control = ClientControllerManager.controllerPos.get(event.getPos());

				control.unattach();

				ClientControllerManager.controllerPos.remove(event.getPos());
			}
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOutEvent(LoggingOut e) {
		GrappleConfig.setServerOptions(null);
	}

	@SubscribeEvent
	public void onKeyInputEvent(Key event) {
		Player player = Minecraft.getInstance().player;
		if (!Minecraft.getInstance().isRunning() || player == null) {
			return;
		}

		GrappleController controller = null;
		if (ClientControllerManager.controllers.containsKey(player.getId())) {
			controller = ClientControllerManager.controllers.get(player.getId());
		}

		if (Minecraft.getInstance().options.keyJump.isDown()) {
			if (controller != null) {
				if (controller instanceof AirfrictionController && ((AirfrictionController) controller).wasSliding) {
					// taken on the next tick's last step, right before the move (see GrappleController.lastStepOfTick)
					((AirfrictionController) controller).slidingJumpQueued = true;
				}
			}
		}

		ClientControllerManager.instance.checkSlide(Minecraft.getInstance().player);
	}

	// a jump press this mod has taken (see onInputUpdate) stays taken until the key is let go
	private boolean jumpPressTaken = false;

	@SubscribeEvent(priority=EventPriority.LOW)
	public void onInputUpdate(MovementInputUpdateEvent event) {
		Player player = Minecraft.getInstance().player;
		if (!Minecraft.getInstance().isRunning() || player == null) {
			return;
		}
		if (!event.getInput().jumping) {
			this.jumpPressTaken = false;
		}

		int id = player.getId();
		if (ClientControllerManager.controllers.containsKey(id)) {
			Input input = event.getInput();
			GrappleController control = ClientControllerManager.controllers.get(id);
			control.receivePlayerMovementMessage(input.leftImpulse, input.forwardImpulse, input.jumping, input.shiftKeyDown);

			boolean overrideMovement = true;
			// creative flight owns movement completely; the controller is passive while flying
			// (rope pays out, no constraint), so vanilla flight must keep its WASD input
			if (player.getAbilities().flying) {
				overrideMovement = false;
			} else if (Minecraft.getInstance().player.onGround()) {
				if (!(control instanceof AirfrictionController) && !(control instanceof ForcefieldController)) {
					overrideMovement = false;
				}
			}
			// the input is only taken while this mod really has the player's motion: another mod's
			// claim (a WallJump cling taking over from the air controller) gets it back at once
			if (ClientControllerManager.motionOwnedElsewhere(player)) {
				overrideMovement = false;
			}

			if (overrideMovement) {
				// Plain air control after a rope release never jumps in mid-air itself, so it leaves
				// a fresh jump press alone there: vanilla (elytra, creative flight) and other mods
				// (WallJump's double jump, which then claims the motion and ends the air control)
				// still see it. A rope, a wall-run, a slide or a grapple jump keep the press, all
				// of it: held on past them it is still theirs, not a new press for someone else.
				if (input.jumping && (control.ownsMotion() || player.onGround())) {
					this.jumpPressTaken = true;
				}
				if (this.jumpPressTaken) {
					input.jumping = false;
				}
				input.down = false;
				input.up = false;
				input.left = false;
				input.right = false;
				input.forwardImpulse = 0;
				input.leftImpulse = 0;
//				input.sneak = false; // fix alternate throw angles
			}
		}
	}

	public float currentCameraTilt = 0;

	@SubscribeEvent
	public void onCameraSetup(ComputeCameraAngles event) {
		Player player = Minecraft.getInstance().player;
		if (!Minecraft.getInstance().isRunning() || player == null) {
			return;
		}

		int id = player.getId();
		int targetCameraTilt = 0;
		if (ClientControllerManager.controllers.containsKey(id)) {
			GrappleController controller = ClientControllerManager.controllers.get(id);
			if (controller instanceof AirfrictionController) {
				AirfrictionController afcontroller = (AirfrictionController) controller;
				if (afcontroller.wasWallrunning) {
					Vec walldirection = afcontroller.getWallDirection();
					if (walldirection != null) {
						Vec lookdirection = afcontroller.toLocal(Vec.lookVec(player)); // in the wall direction's frame
						int dir = lookdirection.cross(walldirection).y > 0 ? 1 : -1;
						targetCameraTilt = dir;
					}
				}
			}
		}

		if (currentCameraTilt != targetCameraTilt) {
			float cameraDiff = targetCameraTilt - currentCameraTilt;
			if (cameraDiff != 0) {
				float anim_s = GrappleConfig.getClientConf().camera.wallrun_camera_animation_s;
				float speed = (anim_s == 0) ? 9999 :  1.0f / (anim_s * 20.0f);
				if (speed > Math.abs(cameraDiff)) {
					currentCameraTilt = targetCameraTilt;
				} else {
					currentCameraTilt += speed * (cameraDiff > 0 ? 1 : -1);
				}
			}
		}

		if (currentCameraTilt != 0) {
			event.setRoll(event.getRoll() + currentCameraTilt*GrappleConfig.getClientConf().camera.wallrun_camera_tilt_degrees);
		}
	}

	public ItemStack getKeypressStack(Player player) {
		if (player != null) {
			ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
			if (stack != null) {
				Item item = stack.getItem();
				if (item instanceof KeypressItem) {
					return stack;
				}
			}

			stack = player.getItemInHand(InteractionHand.OFF_HAND);
			if (stack != null) {
				Item item = stack.getItem();
				if (item instanceof KeypressItem) {
					return stack;
				}
			}
		}
		return null;
	}

	public boolean isLookingAtModifierBlock(Player player) {
		HitResult raytraceresult = Minecraft.getInstance().hitResult;
		if (raytraceresult != null && raytraceresult.getType() == HitResult.Type.BLOCK) {
			BlockHitResult bray = (BlockHitResult) raytraceresult;
			BlockPos pos = bray.getBlockPos();
			BlockState state = player.level().getBlockState(pos);

			return (state.getBlock() == CommonSetup.grappleModifierBlock.get());
		}
		return false;
	}


}