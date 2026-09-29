package net.camacraft.grappleunbound.entities.grapplehook;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;


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

@OnlyIn(Dist.CLIENT)
public class RenderGrapplehookEntity<T extends GrapplehookEntity> extends EntityRenderer<T>
{
    protected final Item item;
    private static final ResourceLocation HOOK_TEXTURES = new ResourceLocation("grappleunbound", "textures/entity/entity_hook.png");
    private static final RenderType HOOK_RENDER = RenderType.entityCutoutNoCull(HOOK_TEXTURES, false);
    private static final ResourceLocation ROPE_TEXTURES = new ResourceLocation("grappleunbound", "textures/entity/rope.png");
    private static final RenderType ROPE_RENDER = RenderType.entitySolid(ROPE_TEXTURES);
    EntityRendererProvider.Context context;

	public RenderGrapplehookEntity(EntityRendererProvider.Context context, Item itemIn) {
		super(context);
		this.item = itemIn;
		this.context = context;
	}

//    public RenderGrapplehookEntity(EntityRenderDispatcher renderManagerIn, Item itemIn)
//    {
//        super(renderManagerIn);
//        this.item = itemIn;
//    }

    /**
     * Actually renders the given argument. This is a synthetic bridge method, always casting down its argument and then
     * handing it off to a worker function which does the actual work. In all probabilty, the class Render is generic
     * (Render<T extends Entity>) and this method has signature public void func_76986_a(T entity, double d, double d1,
     * double d2, float f, float f1). But JAD is pre 1.5 so doe
     */
    @Override
    public void render(T hookEntity, float p_225623_2_, float partialTicks, PoseStack matrix, MultiBufferSource rendertype, int p_225623_6_) {
		if (hookEntity == null || !hookEntity.isAlive()) {
			return;
		}
		
		SegmentHandler segmenthandler = hookEntity.segmentHandler;
		
		LivingEntity e = (LivingEntity) hookEntity.shootingEntity;
		
		if (e == null || !e.isAlive()) {
			return;
		}
		
		LivingEntity playerentity = e;
		
		/** get player hand position **/
		
		// is right hand?
		int hand_right = (playerentity.getMainArm() == HumanoidArm.RIGHT ? 1 : -1) * (hookEntity.rightHand ? 1 : -1);
		
		// attack/swing progress
		float f = playerentity.getAttackAnim(partialTicks);
		float f1 = Mth.sin(Mth.sqrt(f) * (float)Math.PI);
		
		// get the offset from the center of the head to the hand
		Vec hand_offset;
		if ((this.entityRenderDispatcher.options == null || this.entityRenderDispatcher.options.getCameraType().isFirstPerson()) && playerentity == Minecraft.getInstance().player) {
			// if first person
			
			// base hand offset (no swing, when facing +Z)
			double d7 = this.entityRenderDispatcher.options.fov().get();
			d7 = d7 / 100.0D;
			hand_offset = new Vec((double) hand_right * -0.46D * d7, -0.18D * d7, 0.38D);
			// apply swing
			hand_offset = hand_offset.rotatePitch(-f1 * 0.7F);
			hand_offset = hand_offset.rotateYaw(-f1 * 0.5F);
			// apply looking direction
			hand_offset = hand_offset.rotatePitch(-Vec.lerp(partialTicks, playerentity.xRotO, playerentity.getXRot()) * ((float)Math.PI / 180F));
			hand_offset = hand_offset.rotateYaw(Vec.lerp(partialTicks, playerentity.yRotO, playerentity.getYRot()) * ((float)Math.PI / 180F));
		} else {
			// if third person
			
			// base hand offset (no swing, when facing +Z)
			hand_offset = new Vec((double) hand_right * -0.36D, -0.65D + (playerentity.isCrouching() ? -0.1875F : 0.0F), 0.6D);
			// apply swing
			hand_offset = hand_offset.rotatePitch(f1 * 0.7F);
			// apply body rotation
			hand_offset = hand_offset.rotateYaw(Vec.lerp(partialTicks, playerentity.yBodyRotO, playerentity.yBodyRot) * ((float)Math.PI / 180F));
		}
		
		// get the hand position
		hand_offset.y += playerentity.getEyeHeight();
		Vec hand_position = hand_offset.add(Vec.partialPositionVec(playerentity, partialTicks));
        
		
		/** draw hook **/
		
		// get direction of rope where hook is attached
		Vec attach_dir = Vec.motionVec(hookEntity).mult(-1);
		if (attach_dir.length() == 0) {
			if (hookEntity.attach_dir != null) {
				attach_dir = hookEntity.attach_dir;
			} else {
		        if (segmenthandler == null || segmenthandler.segments.size() <= 2) {
		        	attach_dir = getRelativeToEntity(hookEntity, new Vec(hand_position), partialTicks);
		        } else {
		    		Vec from = segmenthandler.segments.get(1);
		    		Vec to = Vec.partialPositionVec(hookEntity, partialTicks);
		    		attach_dir = from.sub(to);
		        }
			}
		}
        attach_dir.normalize_ip();
		if (hookEntity.attached) {
			if (hookEntity.attach_dir != null) {
				attach_dir = hookEntity.attach_dir;
			}
		}
		hookEntity.attach_dir = attach_dir;
		
		// transformation so hook texture is facing the correct way
		matrix.pushPose();
		matrix.scale(0.5F, 0.5F, 0.5F);
		
		matrix.mulPose(new Quaternionf().setAngleAxis((float) (-attach_dir.getYaw()),0, 1, 0 ));
		matrix.mulPose(new Quaternionf().setAngleAxis((float) (attach_dir.getPitch() - 90), 1, 0, 0 ));
		matrix.mulPose(new Quaternionf().setAngleAxis((float) (45 * hand_right), 0, 1, 0 ));
		matrix.mulPose(new Quaternionf().setAngleAxis((float) (-45), 0, 0, 1 ));
		
		// draw hook
		ItemStack stack = this.getStackToRender(hookEntity);
		BakedModel bakedmodel = context.getItemRenderer().getModel(stack, hookEntity.level(), (LivingEntity)null, hookEntity.getId());
        context.getItemRenderer().render(stack, ItemDisplayContext.NONE, false, matrix, rendertype, p_225623_6_, OverlayTexture.NO_OVERLAY, bakedmodel);

		// revert transformation
		matrix.popPose();
		
		/*
		// get direction of rope where hook is attached
		Vec hook_dir;
        if (segmenthandler == null || segmenthandler.segments.size() <= 2) {
        	hook_dir = getRelativeToEntity(hookEntity, new Vec(hand_position), partialTicks);
        } else {
    		Vec from = segmenthandler.segments.get(1);
    		Vec to = Vec.partialPositionVec(hookEntity, partialTicks);
        	hook_dir = from.sub(to);
        }
        hook_dir.normalize_ip();
		
		// transformation so hook texture is facing the camera
		matrix.pushPose();
		matrix.scale(0.5F, 0.5F, 0.5F);
		matrix.mulPose(this.entityRenderDispatcher.cameraOrientation());
		
		Vec cam_pos = new Vec(this.entityRenderDispatcher.camera.getPosition());
		Vec hook_pos = Vec.partialPositionVec(hookEntity, partialTicks);
		
		Vec cam_dir = hook_pos.sub(cam_pos);
		Vec hook_dir_from_cam = hook_dir.removeAlong(cam_dir);
		Vec up_from_cam = new Vec(this.entityRenderDispatcher.camera.getUpVector()).removeAlong(cam_dir);
		double hook_angle_from_cam = hook_dir_from_cam.angle(up_from_cam);
		if (hook_dir_from_cam.dot(cam_dir.cross(up_from_cam)) < 0) {
			hook_angle_from_cam *= -1;
		}
		
		matrix.mulPose(new Quaternion(new Vec(0, 0, 1).toVector3f(), (float) (hook_angle_from_cam + Math.PI*3/4), false));
		
		PoseStack.Pose matrixstack$entry = matrix.last();
		Matrix4f matrix4f = matrixstack$entry.pose();
		Matrix3f matrix3f = matrixstack$entry.normal();
		
		// draw hook texture
		VertexConsumer ivertexbuilder = rendertype.getBuffer(HOOK_RENDER);
		vertex(ivertexbuilder, matrix4f, matrix3f, p_225623_6_, 0.0F, 0, 0, 1);
		vertex(ivertexbuilder, matrix4f, matrix3f, p_225623_6_, 1.0F, 0, 1, 1);
		vertex(ivertexbuilder, matrix4f, matrix3f, p_225623_6_, 1.0F, 1, 1, 0);
		vertex(ivertexbuilder, matrix4f, matrix3f, p_225623_6_, 0.0F, 1, 0, 0);
		
		// revert transformation
		matrix.popPose();
		*/
		
		/** draw rope **/

		// a slack rope sags by the local gravity: straight in VS Genesis's space or an Ad Astra orbit
		this.ropeSag = Math.min(1.0, net.camacraft.camafy.compat.gravity.Gravity.ambientStrength(hookEntity));

		// stretch tint: fade the rope to red as it approaches its snapping stretch
		this.ropeTint = 1.0F;
		double ropeLen = hookEntity.getSyncedRopeLength();
		if (hookEntity.attached && ropeLen > 0.5 && segmenthandler != null) {
			double allowance = net.camacraft.grappleunbound.config.GrappleConfig.getConf().grapplinghook.other.rope_snap_buffer * 3;
			if (allowance > 0.01) {
				double pathLen = segmenthandler.getDist(Vec.partialPositionVec(hookEntity, partialTicks), hand_position);
				double stretch = (pathLen - ropeLen) / allowance;
				this.ropeTint = (float) Math.max(0.15, 1.0 - Math.max(0, stretch));
			}
		}

		// transformation (no tranformation)
        matrix.pushPose();
        PoseStack.Pose matrixstack$entry = matrix.last();
        Matrix4f matrix4f1 = matrixstack$entry.pose();
        Matrix3f matrix3f1 = matrixstack$entry.normal();

        // initialize vertexbuffer (used for drawing)
        VertexConsumer vertexbuffer = rendertype.getBuffer(ROPE_RENDER);
        
        // draw rope
        if (segmenthandler == null) {
        	// if no segmenthandler, straight line from hand to hook
    		drawSegment(new Vec(0,0,0), getRelativeToEntity(hookEntity, new Vec(hand_position), partialTicks), 1.0F, vertexbuffer, matrix4f1, matrix3f1, p_225623_6_);
        } else {
        	for (int i = 0; i < segmenthandler.segments.size() - 1; i++) {
        		Vec from = segmenthandler.segments.get(i);
        		Vec to = segmenthandler.segments.get(i+1);
        		
        		if (i == 0) {
        			from = Vec.partialPositionVec(hookEntity, partialTicks);
        		}
        		if (i + 2 == segmenthandler.segments.size()) {
        			to = hand_position;
        		}
        		
        		from = getRelativeToEntity(hookEntity, from, partialTicks);
        		to = getRelativeToEntity(hookEntity, to, partialTicks);
        		
        		double taut = 1;
        		if (i == segmenthandler.segments.size() - 2) {
        			taut = hookEntity.taut;
        		}
        		
        		drawSegment(from, to, taut, vertexbuffer, matrix4f1, matrix3f1, p_225623_6_);
        	}
        }
        
        // draw tip of rope closest to hand
		Vec hook_pos = Vec.partialPositionVec(hookEntity, partialTicks);
        Vec hand_closest;
        if (segmenthandler == null || segmenthandler.segments.size() <= 2) {
        	hand_closest = hook_pos;
        } else {
        	hand_closest = segmenthandler.segments.get(segmenthandler.segments.size() - 2);
        }
        Vec diff = hand_closest.sub(hand_position);
        Vec forward = diff.changeLen(1);
        Vec up = forward.cross(new Vec(1, 0, 0));
        if (up.length() == 0) {
        	up = forward.cross(new Vec(0, 0, 1));
        }
        up.changeLen_ip(0.025);
        Vec side = forward.cross(up);
        side.changeLen_ip(0.025);
        
        Vec[] corners = new Vec[] {up.mult(-1).add(side.mult(-1)), up.mult(-1).add(side), up.add(side), up.add(side.mult(-1))};
        float[][] uvs = new float[][] {{0, 0.99F}, {0, 1}, {1, 1}, {1, 0.99F}};
        
        for (int size = 0; size < 4; size++) {
            Vec corner = corners[size];
        	Vec normal = corner.normalize(); //.add(forward.normalize().mult(-1)).normalize();
        	Vec cornerpos = getRelativeToEntity(hookEntity, hand_position, partialTicks).add(corner);
        	vertexbuffer.vertex(matrix4f1, (float) cornerpos.x, (float) cornerpos.y, (float) cornerpos.z).color(255, (int) (255 * this.ropeTint), (int) (255 * this.ropeTint), 255).uv(uvs[size][0], uvs[size][1]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(p_225623_6_).normal(matrix3f1, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
        }
        

		matrix.popPose();
        
		
         
		super.render(hookEntity, p_225623_2_, partialTicks, matrix, rendertype, p_225623_6_);
    }
    
    Vec getRelativeToEntity(GrapplehookEntity hookEntity, Vec inVec, float partialTicks) {
    	return inVec.sub(Vec.partialPositionVec(hookEntity, partialTicks));
    }
    
    // vertex for the hook
    private static void vertex(VertexConsumer p_229106_0_, Matrix4f p_229106_1_, Matrix3f p_229106_2_, int p_229106_3_, float p_229106_4_, int p_229106_5_, int p_229106_6_, int p_229106_7_) {
        p_229106_0_.vertex(p_229106_1_, p_229106_4_ - 0.5F, (float)p_229106_5_ - 0.5F, 0.0F).color(255, 255, 255, 255).uv((float)p_229106_6_, (float)p_229106_7_).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(p_229106_3_).normal(p_229106_2_, 0.0F, 1.0F, 0.0F).endVertex();
     }

    // white when relaxed, fading to red as the rope approaches its snapping stretch
    private float ropeTint = 1.0F;
    // how much of its slack the rope hangs: the local gravity, up to 1
    private double ropeSag = 1.0;

    // draw a segment of the rope
    public void drawSegment(Vec start, Vec finish, double taut, VertexConsumer vertexbuffer, Matrix4f matrix, Matrix3f matrix3, int p_225623_6_) {
        net.camacraft.grappleunbound.client.RopeRenderer.drawSegmentSag(start, finish, (1 - taut) * 0.375 * this.ropeSag, vertexbuffer, matrix, matrix3, p_225623_6_, 1.0F, this.ropeTint, this.ropeTint);
    }

    @Override
    public boolean shouldRender(T p_225626_1_, Frustum p_225626_2_, double p_225626_3_, double p_225626_5_, double p_225626_7_) {
		return true;
	}

	private ItemStack cachedRenderStack = null;

	public ItemStack getStackToRender(T entityIn)
    {
		// cached: this runs every frame per hook and the stack never changes
		if (cachedRenderStack == null) {
			ItemStack stack = new ItemStack(this.item);
			CompoundTag tag = stack.getOrCreateTag();
			tag.putBoolean("hook", true);
			stack.setTag(tag);
			cachedRenderStack = stack;
		}
        return cachedRenderStack;
    }

    /**
     * Returns the location of an entity's texture. Doesn't seem to be called unless you call Render.bindEntityTexture.
     */
	@Override
	public ResourceLocation getTextureLocation(T entity) {
        return HOOK_TEXTURES;
	}
}
