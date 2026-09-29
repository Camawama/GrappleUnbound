package net.camacraft.grappleunbound.controllers;

import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public class ForcefieldController extends GrappleController {
	public ForcefieldController(int grapplehookEntityId, int entityId, Level world, Vec pos, int id) {
		super(grapplehookEntityId, entityId, world, pos, id, null);
		
		this.playerMovementMult = 1;
	}

	public void updatePlayerPos() {
		Entity entity = this.entity;
		
		if (this.attached) {
			if(entity != null) {
				if (true) {
					this.readFrame();
					this.updateMotionClaim();
					this.normalGround(false);
					this.normalCollisions(false);
//					this.applyAirFriction();
					
					Vec playerpos = Vec.positionVec(entity);
					
//					double dist = oldspherevec.length();
					
					if (playerSneak) {
						motion.mult_ip(0.95);
					}
					applyPlayerMovement();
					
					Vec blockpush = this.toLocal(checkRepel(playerpos, entity.level()));
					blockpush.mult_ip(0.5);
					blockpush = new Vec(blockpush.x*0.5, blockpush.y*GrappleController.repelLift(entity), blockpush.z*0.5);
					this.motion.add_ip(blockpush);
					
					if (!entity.onGround()) {
						motion.add_ip(this.gravityStep());
					}
					
					this.writeMotion(motion);
					
					this.updateServerPos();
				}
			}
		}
	}}
