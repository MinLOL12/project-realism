package com.projectrealism.client;

import com.projectrealism.ProjectRealism;
import com.projectrealism.anatomy.AnatomyBlocks;
import com.projectrealism.anatomy.CarcassBlock;
import com.projectrealism.anatomy.CarcassBlockEntity;
import com.projectrealism.anatomy.CarcassState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/**
 * Clean, non-graphic diagrammatic 3D renderer for persistent animal carcasses. Distinct geometric
 * layers (outer fleece coat, hide pelt, cream fat layer, muted muscle groups, horns, and pale
 * ivory internal skeleton) visibly peel away or shrink as each anatomical section is harvested.
 */
@Environment(EnvType.CLIENT)
public final class AnatomyBlockRenderers {
	private static final Identifier CANVAS_TEX = ProjectRealism.id("textures/misc/material_canvas.png");

	private AnatomyBlockRenderers() {
	}

	public static void register() {
		BlockEntityRendererFactories.register(AnatomyBlocks.CARCASS_ENTITY,
				ctx -> new CarcassRenderer());
	}

	private static class CarcassRenderer implements BlockEntityRenderer<CarcassBlockEntity> {
		@Override
		public void render(CarcassBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			CarcassState c = be.carcass();
			matrices.push();
			Direction dir = be.getCachedState().get(CarcassBlock.FACING);
			matrices.translate(0.5, 0.01, 0.5);
			float yaw = switch (dir) {
				case SOUTH -> 180f;
				case WEST -> 90f;
				case EAST -> -90f;
				default -> 0f;
			};
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));

			float s = c.species.visualScale * (float) (0.72 + 0.28 * c.ageMaturity);
			matrices.scale(s, s, s);

			VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(CANVAS_TEX));
			MatrixStack.Entry entry = matrices.peek();

			// Subtle non-graphic spoilage & frost color tinting
			float spoil = (float) c.spoilageFrac;
			boolean cold = c.bodyTempK <= 273.15;

			// 1. Pale ivory internal skeleton (spine beam, skull block & remaining rib/limb segments)
			if (!c.bonesRemaining.isEmpty()) {
				int boneTint = cold ? 218 : (int) (235 - spoil * 55);
				drawBox(vc, entry, light, -0.07f, 0.08f, -0.32f, 0.07f, 0.18f, 0.32f, boneTint, boneTint - 6, boneTint - 22);
				drawBox(vc, entry, light, -0.09f, 0.10f, -0.44f, 0.09f, 0.24f, -0.30f, boneTint, boneTint - 8, boneTint - 24);
				int limbBars = Math.min(4, c.bonesRemaining.size());
				for (int i = 0; i < limbBars; i++) {
					float lz = (i < 2) ? -0.22f : 0.20f;
					float lx = (i % 2 == 0) ? -0.22f : 0.12f;
					drawBox(vc, entry, light, lx, 0.02f, lz, lx + 0.10f, 0.10f, lz + 0.08f, boneTint, boneTint - 8, boneTint - 22);
				}
			}

			// 2. Clean geometric muscle-group blocks (absent once harvested)
			int mR = Math.max(85, (int) (168 - spoil * 65 - (cold ? 18 : 0)));
			int mG = Math.max(70, (int) (96 + spoil * 25 + (cold ? 12 : 0)));
			int mB = Math.max(65, (int) (84 - spoil * 22 + (cold ? 35 : 0)));
			if (c.primeMeatRemainingKg > 0.04) {
				drawBox(vc, entry, light, -0.14f, 0.16f, -0.20f, 0.14f, 0.30f, 0.16f, mR, mG, mB);
			}
			if (c.fattyMeatRemainingKg > 0.04) {
				drawBox(vc, entry, light, -0.22f, 0.06f, -0.24f, 0.22f, 0.22f, 0.16f, mR - 12, mG - 6, mB - 4);
			}
			if (c.toughMeatRemainingKg > 0.04) {
				drawBox(vc, entry, light, -0.20f, 0.05f, 0.14f, 0.20f, 0.28f, 0.36f, mR - 20, mG - 10, mB - 8);
			}

			// 3. Soft butter-cream subcutaneous fat mantle
			if (c.fatRemainingKg > 0.05) {
				drawBox(vc, entry, light, -0.24f, 0.14f, -0.24f, 0.24f, 0.32f, 0.28f, 232, 214, 158);
			}

			// 4. Outer species coat / hide shell (peels backward cleanly as skinProgress approaches 1.0)
			if (c.hidePresent) {
				float zFront = -0.34f + (float) (c.skinProgress * 0.42);
				int[] coat = coatColor(c.species.id);
				drawBox(vc, entry, light, -0.26f, 0.04f, zFront, 0.26f, 0.34f, 0.36f, coat[0], coat[1], coat[2]);
			}

			// 5. Outer wool fleece jacket on un-shorn sheep
			if (c.woolRemainingKg > 0.05) {
				drawBox(vc, entry, light, -0.29f, 0.06f, -0.32f, 0.29f, 0.38f, 0.34f, 238, 234, 224);
			}

			// 6. Distinct curved horns on head
			if (c.hornsRemaining > 0) {
				drawBox(vc, entry, light, -0.15f, 0.22f, -0.44f, -0.09f, 0.32f, -0.38f, 188, 174, 146);
				if (c.hornsRemaining > 1) {
					drawBox(vc, entry, light, 0.09f, 0.22f, -0.44f, 0.15f, 0.32f, -0.38f, 188, 174, 146);
				}
			}

			matrices.pop();
		}
	}

	private static int[] coatColor(String speciesId) {
		return switch (speciesId) {
			case "cow" -> new int[]{110, 74, 48};
			case "horse" -> new int[]{142, 92, 54};
			case "pig" -> new int[]{214, 154, 142};
			case "sheep" -> new int[]{176, 162, 138};
			case "goat" -> new int[]{188, 180, 166};
			case "wolf" -> new int[]{154, 152, 148};
			case "rabbit" -> new int[]{162, 128, 92};
			default -> new int[]{172, 138, 102};
		};
	}

	private static void drawBox(VertexConsumer vc, MatrixStack.Entry e, int light,
								float x0, float y0, float z0, float x1, float y1, float z1,
								int r, int g, int b) {
		// Top (+Y)
		quad(vc, e, light, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, 0f, 1f, 0f);
		// Bottom (-Y)
		quad(vc, e, light, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, (int)(r*0.7f), (int)(g*0.7f), (int)(b*0.7f), 0f, -1f, 0f);
		// Front (-Z)
		quad(vc, e, light, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, (int)(r*0.88f), (int)(g*0.88f), (int)(b*0.88f), 0f, 0f, -1f);
		// Back (+Z)
		quad(vc, e, light, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, (int)(r*0.88f), (int)(g*0.88f), (int)(b*0.88f), 0f, 0f, 1f);
		// Left (-X)
		quad(vc, e, light, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, (int)(r*0.82f), (int)(g*0.82f), (int)(b*0.82f), -1f, 0f, 0f);
		// Right (+X)
		quad(vc, e, light, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, (int)(r*0.82f), (int)(g*0.82f), (int)(b*0.82f), 1f, 0f, 0f);
	}

	private static void quad(VertexConsumer vc, MatrixStack.Entry e, int light,
							 float x0, float y0, float z0,
							 float x1, float y1, float z1,
							 float x2, float y2, float z2,
							 float x3, float y3, float z3,
							 int r, int g, int b, float nx, float ny, float nz) {
		vc.vertex(e, x0, y0, z0).color(r, g, b, 255).texture(0f, 0f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
		vc.vertex(e, x1, y1, z1).color(r, g, b, 255).texture(0f, 1f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
		vc.vertex(e, x2, y2, z2).color(r, g, b, 255).texture(1f, 1f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
		vc.vertex(e, x3, y3, z3).color(r, g, b, 255).texture(1f, 0f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
	}
}
