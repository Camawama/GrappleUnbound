package net.camacraft.grappleunbound.network;

import net.camacraft.grappleunbound.client.ClientProxyInterface;
import net.camacraft.grappleunbound.entities.grapplehook.GrapplehookEntity;
import net.camacraft.grappleunbound.entities.grapplehook.SegmentHandler;
import net.camacraft.grappleunbound.utils.GrappleCustomization;
import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedList;

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

public class GrappleAttachMessage extends BaseMessageClient {
   
	public int id;
	public double x;
	public double y;
	public double z;
	public int controlId;
	public int entityId;
	public BlockPos blockPos;
	public LinkedList<Vec> segments;
	public LinkedList<Direction> segmentTopSides;
	public LinkedList<Direction> segmentBottomSides;
	public LinkedList<Long> segmentShipIds;
	public LinkedList<Vec> segmentShipLocals;
	public GrappleCustomization custom;
    public long shipId;
    public double localX;
    public double localY;
    public double localZ;

    public GrappleAttachMessage(FriendlyByteBuf buf) {
    	super(buf);
    }

    public GrappleAttachMessage(int id, double x, double y, double z, int controlid, int entityid, BlockPos blockpos, SegmentHandler segmentHandler, GrappleCustomization custom, long shipId, double localX, double localY, double localZ) {
    	this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.controlId = controlid;
        this.entityId = entityid;
        this.blockPos = blockpos;
        this.segments = segmentHandler.segments;
        this.segmentTopSides = segmentHandler.segmentTopSides;
        this.segmentBottomSides = segmentHandler.segmentBottomSides;
        this.segmentShipIds = segmentHandler.segmentShipIds;
        this.segmentShipLocals = segmentHandler.segmentShipLocals;
        this.custom = custom;
        this.shipId = shipId;
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
    }

    public void decode(FriendlyByteBuf buf) {
    	this.id = buf.readInt();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.controlId = buf.readInt();
        this.entityId = buf.readInt();
        int blockx = buf.readInt();
        int blocky = buf.readInt();
        int blockz = buf.readInt();
        this.blockPos = new BlockPos(blockx, blocky, blockz);

        this.custom = new GrappleCustomization();
        this.custom.readFromBuf(buf);

        int size = buf.readInt();
        this.segments = new LinkedList<Vec>();
        this.segmentBottomSides = new LinkedList<Direction>();
        this.segmentTopSides = new LinkedList<Direction>();
        this.segmentShipIds = new LinkedList<Long>();
        this.segmentShipLocals = new LinkedList<Vec>();

		segments.add(new Vec(0, 0, 0));
		segmentBottomSides.add(null);
		segmentTopSides.add(null);
		segmentShipIds.add(SegmentHandler.NO_SHIP);
		segmentShipLocals.add(null);

		for (int i = 1; i < size-1; i++) {
        	this.segments.add(new Vec(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        	this.segmentBottomSides.add(buf.readEnum(Direction.class));
        	this.segmentTopSides.add(buf.readEnum(Direction.class));
        	long bendShipId = buf.readLong();
        	Vec bendLocal = new Vec(buf.readDouble(), buf.readDouble(), buf.readDouble());
        	this.segmentShipIds.add(bendShipId);
        	this.segmentShipLocals.add(bendShipId != SegmentHandler.NO_SHIP ? bendLocal : null);
        }

		segments.add(new Vec(0, 0, 0));
		segmentBottomSides.add(null);
		segmentTopSides.add(null);
		segmentShipIds.add(SegmentHandler.NO_SHIP);
		segmentShipLocals.add(null);

        if (buf.readBoolean()) {
            this.shipId = buf.readLong();
            this.localX = buf.readDouble();
            this.localY = buf.readDouble();
            this.localZ = buf.readDouble();
        } else {
            this.shipId = -1;
        }
    }

    public void encode(FriendlyByteBuf buf) {
    	buf.writeInt(this.id);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeInt(this.controlId);
        buf.writeInt(this.entityId);
        buf.writeInt(this.blockPos.getX());
        buf.writeInt(this.blockPos.getY());
        buf.writeInt(this.blockPos.getZ());

        this.custom.writeToBuf(buf);

        buf.writeInt(this.segments.size());
        for (int i = 1; i < this.segments.size()-1; i++) {
        	buf.writeDouble(this.segments.get(i).x);
        	buf.writeDouble(this.segments.get(i).y);
        	buf.writeDouble(this.segments.get(i).z);
        	buf.writeEnum(this.segmentBottomSides.get(i));
        	buf.writeEnum(this.segmentTopSides.get(i));
        	long bendShipId = this.segmentShipIds.get(i);
        	Vec bendLocal = this.segmentShipLocals.get(i);
        	buf.writeLong(bendShipId);
        	buf.writeDouble(bendLocal != null ? bendLocal.x : 0);
        	buf.writeDouble(bendLocal != null ? bendLocal.y : 0);
        	buf.writeDouble(bendLocal != null ? bendLocal.z : 0);
        }

        // explicit flag instead of trailing-bytes sniffing, so fields can be appended safely later
        buf.writeBoolean(this.shipId != -1);
        if (this.shipId != -1) {
            buf.writeLong(this.shipId);
            buf.writeDouble(this.localX);
            buf.writeDouble(this.localY);
            buf.writeDouble(this.localZ);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void processMessage(NetworkEvent.Context ctx) {
		Level world = Minecraft.getInstance().level;
    	Entity grapple = world.getEntity(this.id);
    	if (grapple instanceof GrapplehookEntity) {
            GrapplehookEntity hook = (GrapplehookEntity) grapple;
        	hook.clientAttach(this.x, this.y, this.z);
            
            if (this.shipId != -1) {
                hook.getPersistentData().putLong("vs_ship_id", this.shipId);
                hook.getPersistentData().putDouble("vs_local_x", this.localX);
                hook.getPersistentData().putDouble("vs_local_y", this.localY);
                hook.getPersistentData().putDouble("vs_local_z", this.localZ);
            }

        	SegmentHandler segmenthandler = hook.segmentHandler;
        	segmenthandler.segments = this.segments;
        	segmenthandler.segmentBottomSides = this.segmentBottomSides;
        	segmenthandler.segmentTopSides = this.segmentTopSides;
        	segmenthandler.segmentShipIds = this.segmentShipIds;
        	segmenthandler.segmentShipLocals = this.segmentShipLocals;
        	
        	Entity player = world.getEntity(this.entityId);
        	// player entity may not be resolved on this client yet (respawn/dimension-change race)
        	Vec playerpos = (player != null) ? Vec.positionVec(player) : new Vec(this.x, this.y, this.z);
        	segmenthandler.forceSetPos(new Vec(this.x, this.y, this.z), playerpos);
    	} else {
    	}
    	            	
    	ClientProxyInterface.proxy.createControl(this.controlId, this.id, this.entityId, world, new Vec(this.x, this.y, this.z), this.blockPos, this.custom);
    }
}
