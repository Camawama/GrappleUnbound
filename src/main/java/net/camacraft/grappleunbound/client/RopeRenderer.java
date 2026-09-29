package net.camacraft.grappleunbound.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.camacraft.grappleunbound.utils.Vec;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws a sagging rope span of the grappling hook's rope as a textured quad-strip box.
 */
@OnlyIn(Dist.CLIENT)
public class RopeRenderer {

    /**
     * Draw one rope span from start to finish (both relative to the current pose origin) with an
     * explicit mid-span sag depth in blocks and a color tint (used to fade the rope red as it
     * stretches).
     */
    public static void drawSegmentSag(Vec start, Vec finish, double sagDepth, VertexConsumer vertexbuffer, Matrix4f matrix, Matrix3f matrix3, int light, float red, float green, float blue) {
        if (start.sub(finish).length() < 0.05) {
            return;
        }
        int cr = (int) (255 * red);
        int cg = (int) (255 * green);
        int cb = (int) (255 * blue);
        // 0.375 is the depth the (frac-based) parabola below evaluates to at mid-span per unit
        double taut = 1 - Math.max(0, sagDepth) / 0.375;

        int number_squares = 16;
        if (sagDepth <= 0.001) {
            number_squares = 1;
        }

        Vec diff = finish.sub(start);

        Vec forward = diff.changeLen(1);
        Vec up = forward.cross(new Vec(1, 0, 0));
        if (up.length() == 0) {
            up = forward.cross(new Vec(0, 0, 1));
        }
        up.changeLen_ip(0.025);
        Vec side = forward.cross(up);
        side.changeLen_ip(0.025);

        Vec[] corners = new Vec[] {up.mult(-1).add(side.mult(-1)), up.add(side.mult(-1)), up.add(side), up.mult(-1).add(side)};

        for (int size = 0; size < 4; size++) {
            Vec corner1 = corners[size];
            Vec corner2 = corners[(size + 1) % 4];

            Vec normal1 = corner1.normalize();
            Vec normal2 = corner2.normalize();

            for (int square_num = 0; square_num < number_squares; square_num++)
            {
                float squarefrac1 = (float)square_num / (float) number_squares;
                Vec pos1 = start.add(diff.mult(squarefrac1));
                pos1.y += - (1 - taut) * (0.25 - Math.pow((squarefrac1 - 0.5), 2)) * 1.5;
                float squarefrac2 = ((float) square_num+1) / (float) number_squares;
                Vec pos2 = start.add(diff.mult(squarefrac2));
                pos2.y += - (1 - taut) * (0.25 - Math.pow((squarefrac2 - 0.5), 2)) * 1.5;

                Vec corner1pos1 = pos1.add(corner1);
                Vec corner2pos1 = pos1.add(corner2);
                Vec corner1pos2 = pos2.add(corner1);
                Vec corner2pos2 = pos2.add(corner2);

                vertexbuffer.vertex(matrix, (float) corner1pos1.x, (float) corner1pos1.y, (float) corner1pos1.z).color(cr, cg, cb, 255).uv(0, squarefrac1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(matrix3, (float) normal1.x, (float) normal1.y, (float) normal1.z).endVertex();
                vertexbuffer.vertex(matrix, (float) corner2pos1.x, (float) corner2pos1.y, (float) corner2pos1.z).color(cr, cg, cb, 255).uv(1, squarefrac1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(matrix3, (float) normal2.x, (float) normal2.y, (float) normal2.z).endVertex();
                vertexbuffer.vertex(matrix, (float) corner2pos2.x, (float) corner2pos2.y, (float) corner2pos2.z).color(cr, cg, cb, 255).uv(1, squarefrac2).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(matrix3, (float) normal2.x, (float) normal2.y, (float) normal2.z).endVertex();
                vertexbuffer.vertex(matrix, (float) corner1pos2.x, (float) corner1pos2.y, (float) corner1pos2.z).color(cr, cg, cb, 255).uv(0, squarefrac2).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(matrix3, (float) normal1.x, (float) normal1.y, (float) normal1.z).endVertex();
            }
        }
    }
}
