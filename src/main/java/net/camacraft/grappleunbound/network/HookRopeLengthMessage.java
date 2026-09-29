package net.camacraft.grappleunbound.network;

import net.camacraft.grappleunbound.config.GrappleConfig;
import net.camacraft.grappleunbound.entities.grapplehook.GrapplehookEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

/**
 * Keeps the server's copy of a hook's rope length in step with the owning client, which is the
 * authority while swinging (climbing and reeling change r client-side only). The server needs a
 * current rope length to compute rope tension on Valkyrien Skies ships.
 */
public class HookRopeLengthMessage extends BaseMessageServer {

	public int hookId;
	public double ropeLength;

	public HookRopeLengthMessage(int hookId, double ropeLength) {
		this.hookId = hookId;
		this.ropeLength = ropeLength;
	}

	public HookRopeLengthMessage(FriendlyByteBuf buf) {
		super(buf);
	}

	public void decode(FriendlyByteBuf buf) {
		this.hookId = buf.readInt();
		this.ropeLength = buf.readDouble();
	}

	public void encode(FriendlyByteBuf buf) {
		buf.writeInt(this.hookId);
		buf.writeDouble(this.ropeLength);
	}

	public void processMessage(NetworkEvent.Context ctx) {
		ServerPlayer player = ctx.getSender();
		if (player == null) {
			return;
		}
		Entity entity = player.level().getEntity(this.hookId);
		if (!(entity instanceof GrapplehookEntity)) {
			return;
		}
		GrapplehookEntity hook = (GrapplehookEntity) entity;
		// only the hook's owner may resize its rope, and only within the hook's own limits
		if (hook.shootingEntityID != player.getId()) {
			return;
		}
		double max = hook.customization.maxlen + GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer;
		hook.r = Math.max(1.0, Math.min(max, this.ropeLength));
		// mirrored to all clients for the rope stretch tint
		hook.setSyncedRopeLength(hook.r);
	}
}
