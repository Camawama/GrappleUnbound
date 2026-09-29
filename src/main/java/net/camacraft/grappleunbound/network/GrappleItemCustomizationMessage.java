package net.camacraft.grappleunbound.network;

import net.camacraft.grappleunbound.common.CommonSetup;
import net.camacraft.grappleunbound.items.GrapplehookItem;
import net.camacraft.grappleunbound.utils.GrappleCustomization;
import net.camacraft.grappleunbound.utils.HookUpgrades;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * Applies customization to the grappling hook the player is holding, sent by the in-hand
 * customization screen. Options are validated against the upgrade categories baked into the
 * item (smithing upgrades), mirroring the modifier-block flow.
 */
public class GrappleItemCustomizationMessage extends BaseMessageServer {

	public boolean mainHand;
	public GrappleCustomization custom;

	public GrappleItemCustomizationMessage(boolean mainHand, GrappleCustomization custom) {
		this.mainHand = mainHand;
		this.custom = custom;
	}

	public GrappleItemCustomizationMessage(FriendlyByteBuf buf) {
		super(buf);
	}

	public void decode(FriendlyByteBuf buf) {
		this.mainHand = buf.readBoolean();
		this.custom = new GrappleCustomization();
		this.custom.readFromBuf(buf);
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeBoolean(this.mainHand);
		this.custom.writeToBuf(buf);
	}

	public void processMessage(NetworkEvent.Context ctx) {
		ServerPlayer player = ctx.getSender();
		if (player == null) {
			return;
		}
		ItemStack stack = player.getItemInHand(this.mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
		if (!(stack.getItem() instanceof GrapplehookItem)) {
			return;
		}

		GrappleCustomization validated = GrappleModifierMessage.validate(
				this.custom, category -> HookUpgrades.has(stack, category), player.isCreative());
		CommonSetup.grapplingHookItem.get().setCustomOnServer(stack, validated, player);
	}
}
