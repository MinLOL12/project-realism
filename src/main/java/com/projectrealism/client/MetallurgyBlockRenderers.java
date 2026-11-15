package com.projectrealism.client;

import com.projectrealism.ProjectRealism;
import com.projectrealism.knapping.FractureEngine;
import com.projectrealism.knapping.HaftData;
import com.projectrealism.knapping.KnappingStoneBlock;
import com.projectrealism.knapping.VoxelRockGrid;
import com.projectrealism.metallurgy.CarvableMoldBlock;
import com.projectrealism.metallurgy.CarvableMoldBlockEntity;
import com.projectrealism.metallurgy.DynamicMetalItem;
import com.projectrealism.metallurgy.DynamicMetalState;
import com.projectrealism.metallurgy.ForgeEngine;
import com.projectrealism.metallurgy.MetalVolumeGrid;
import com.projectrealism.metallurgy.MetallurgyBlocks;
import com.projectrealism.metallurgy.MetallurgyItems;
import com.projectrealism.metallurgy.MoldGrid;
import com.projectrealism.metallurgy.PlacedMetalBlock;
import com.projectrealism.metallurgy.PlacedMetalBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Live volumetric 3D renderer for placed metal forgings, held {@link DynamicMetalItem}s (with alloy
 * color, blackbody thermal incandescence, and mounted handles), and carved {@link CarvableMoldBlockEntity} molds.
 */
@Environment(EnvType.CLIENT)
public final class MetallurgyBlockRenderers {
	private static final Identifier CANVAS_TEX = ProjectRealism.id("textures/misc/material_canvas.png");

	private MetallurgyBlockRenderers() {
	}

	public static void register() {
		BlockEntityRendererFactories.register(MetallurgyBlocks.PLACED_METAL_ENTITY,
				ctx -> new PlacedMetalRenderer());
		BlockEntityRendererFactories.register(MetallurgyBlocks.CARVABLE_MOLD_ENTITY,
				ctx -> new CarvableMoldRenderer());

		BuiltinItemRendererRegistry.INSTANCE.register(MetallurgyItems.DYNAMIC_METAL,
				(stack, mode, matrices, consumers, light, overlay) -> {
					DynamicMetalState state = DynamicMetalItem.readState(stack);
					matrices.push();
					if (mode == ModelTransformationMode.GUI) {
						matrices.translate(0.5, 0.36, 0.5);
						matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(26f));
						matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-38f));
						matrices.scale(0.82f, 0.82f, 0.82f);
						matrices.translate(-0.5, -0.25, -0.5);
					} else if (mode == ModelTransformationMode.FIRST_PERSON_RIGHT_HAND
							|| mode == ModelTransformationMode.FIRST_PERSON_LEFT_HAND) {
						matrices.translate(0.5, 0.42, 0.5);
						matrices.scale(0.75f, 0.75f, 0.75f);
						matrices.translate(-0.5, -0.25, -0.5);
					} else {
						matrices.translate(0.5, 0.40, 0.5);
						matrices.scale(0.70f, 0.70f, 0.70f);
						matrices.translate(-0.5, -0.25, -0.5);
					}
					renderMetalGeometry(state, matrices, consumers, light, null);
					matrices.pop();
				});
	}

	private static class PlacedMetalRenderer implements BlockEntityRenderer<PlacedMetalBlockEntity> {
		@Override
		public void render(PlacedMetalBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			DynamicMetalState s = be.metal();
			Direction dir = be.getCachedState().get(PlacedMetalBlock.FACING);

			FractureEngine.VoxelHit aimHit = null;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player != null && mc.crosshairTarget instanceof BlockHitResult bhr
					&& bhr.getType() == HitResult.Type.BLOCK
					&& bhr.getBlockPos().equals(be.getPos())) {
				Vec3d eyeLocal = KnappingStoneBlock.worldToVoxelPoint(dir, be.getPos(), mc.player.getEyePos());
				Vec3d hitLocal = KnappingStoneBlock.worldToVoxelPoint(dir, be.getPos(), bhr.getPos());
				Vec3d rayDir = hitLocal.subtract(eyeLocal).normalize();
				Vec3d start = hitLocal.subtract(rayDir.multiply(0.18));
				aimHit = ForgeEngine.raycastMetal(s.grid, start, rayDir);
			}

			matrices.push();
			matrices.translate(0.5, 0.0, 0.5);
			float yaw = switch (dir) {
				case SOUTH -> 180f;
				case WEST -> 90f;
				case EAST -> -90f;
				default -> 0f;
			};
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
			matrices.translate(-0.5, 0.0, -0.5);
			renderMetalGeometry(s, matrices, consumers, light, aimHit);
			matrices.pop();
		}
	}

	private static class CarvableMoldRenderer implements BlockEntityRenderer<CarvableMoldBlockEntity> {
		@Override
		public void render(CarvableMoldBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			Direction dir = be.getCachedState().get(CarvableMoldBlock.FACING);
			matrices.push();
			matrices.translate(0.5, 0.0, 0.5);
			float yaw = switch (dir) {
				case SOUTH -> 180f;
				case WEST -> 90f;
				case EAST -> -90f;
				default -> 0f;
			};
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
			matrices.translate(-0.5, 0.0, -0.5);

			VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(CANVAS_TEX));
			MatrixStack.Entry e = matrices.peek();
			MoldGrid mold = be.mold();
			float cell = 1.0f / MoldGrid.SIZE;
			int mr = 165, mg = 148, mb = 128;

			int topY = mold.closedCavity ? 15 : 11;
			for (int y = 0; y <= topY; y++) {
				float y0 = y * cell, y1 = y0 + cell;
				for (int z = 0; z < MoldGrid.SIZE; z++) {
					float z0 = z * cell, z1 = z0 + cell;
					for (int x = 0; x < MoldGrid.SIZE; x++) {
						if (mold.isCavity(x, y, z)) continue;
						// Only draw exposed boundary voxels of the mold block
						boolean expUp = (y == topY) || mold.isCavity(x, y + 1, z);
						boolean expDn = (y == 0) || mold.isCavity(x, y - 1, z);
						boolean expN = (z == 0) || mold.isCavity(x, y, z - 1);
						boolean expS = (z == MoldGrid.SIZE - 1) || mold.isCavity(x, y, z + 1);
						boolean expW = (x == 0) || mold.isCavity(x - 1, y, z);
						boolean expE = (x == MoldGrid.SIZE - 1) || mold.isCavity(x + 1, y, z);
						if (!expUp && !expDn && !expN && !expS && !expW && !expE) continue;

						float x0 = x * cell, x1 = x0 + cell;
						int shade = ((x + z) & 1) == 0 ? 4 : -4;
						int r = clamp255(mr + shade), g = clamp255(mg + shade), b = clamp255(mb + shade);
						if (expUp) quad(vc, e, light, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, 0, 1, 0);
						if (expDn) quad(vc, e, light, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r-30, g-30, b-25, 0, -1, 0);
						if (expN) quad(vc, e, light, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r-18, g-18, b-15, 0, 0, -1);
						if (expS) quad(vc, e, light, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r-18, g-18, b-15, 0, 0, 1);
						if (expW) quad(vc, e, light, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r-24, g-24, b-20, -1, 0, 0);
						if (expE) quad(vc, e, light, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r-24, g-24, b-20, 1, 0, 0);
					}
				}
			}

			if (be.hasSolidifiedCasting() && be.casting() != null) {
				renderMetalGeometry(be.casting(), matrices, consumers, light, null);
			}
			matrices.pop();
		}
	}

	private static void renderMetalGeometry(DynamicMetalState state, MatrixStack matrices,
											VertexConsumerProvider consumers, int light,
											FractureEngine.VoxelHit aimHit) {
		VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(CANVAS_TEX));
		MatrixStack.Entry e = matrices.peek();
		MetalVolumeGrid grid = state.grid;

		int baseR = state.alloy.colorR();
		int baseG = state.alloy.colorG();
		int baseB = state.alloy.colorB();

		// Blackbody thermal incandescence above ~760 K (cherry red -> orange -> white-yellow)
		int glowLight = light;
		if (state.temperatureK >= 760.0) {
			double h = Math.min(1.0, (state.temperatureK - 760.0) / 880.0);
			int gr = (int) Math.round(215 + 40 * h);
			int gg = (int) Math.round(45 + 190 * Math.pow(h, 1.3));
			int gb = (int) Math.round(12 + 170 * Math.pow(h, 2.2));
			baseR = (int) Math.round(baseR * (1.0 - h) + gr * h);
			baseG = (int) Math.round(baseG * (1.0 - h) + gg * h);
			baseB = (int) Math.round(baseB * (1.0 - h) + gb * h);
			glowLight = Math.max(light, 0xF000F0);
		}

		float cell = 1.0f / MetalVolumeGrid.SIZE;
		for (int y = 0; y < MetalVolumeGrid.SIZE; y++) {
			float y0 = y * cell;
			for (int z = 0; z < MetalVolumeGrid.SIZE; z++) {
				float z0 = z * cell, z1 = z0 + cell;
				for (int x = 0; x < MetalVolumeGrid.SIZE; x++) {
					int fill = grid.getFill(x, y, z);
					if (fill < MetalVolumeGrid.SOLID_THRESHOLD) continue;
					float fillFrac = fill / (float) MetalVolumeGrid.FULL_CELL;
					float y1 = y0 + cell * (0.28f + 0.72f * fillFrac);
					float x0 = x * cell, x1 = x0 + cell;

					boolean aimed = aimHit != null && aimHit.x() == x && aimHit.y() == y && aimHit.z() == z;
					double slag = grid.micro.cellAtVoxel(x, y, z).slagInclusion;
					double frac = grid.micro.cellAtVoxel(x, y, z).fractureDamage;

					int r = baseR, g = baseG, b = baseB;
					if (slag > 0.04) {
						int dark = (int) Math.min(55, slag * 190);
						r = clamp255(r - dark);
						g = clamp255(g - dark);
						b = clamp255(b - dark);
					}
					if (frac > 0.15) {
						r = clamp255(r - 28);
						g = clamp255(g - 32);
						b = clamp255(b - 32);
					}
					if (aimed) {
						r = clamp255(r + 65);
						g = clamp255(g + 70);
						b = clamp255(b + 30);
					}

					if (!grid.isSolid(x, y + 1, z) || fill < 900) {
						quad(vc, e, glowLight, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, 0f, 1f, 0f);
					}
					if (!grid.isSolid(x, y - 1, z)) {
						quad(vc, e, glowLight, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, (int)(r*0.65f), (int)(g*0.65f), (int)(b*0.65f), 0f, -1f, 0f);
					}
					if (!grid.isSolid(x, y, z - 1)) {
						quad(vc, e, glowLight, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, (int)(r*0.86f), (int)(g*0.86f), (int)(b*0.86f), 0f, 0f, -1f);
					}
					if (!grid.isSolid(x, y, z + 1)) {
						quad(vc, e, glowLight, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, (int)(r*0.86f), (int)(g*0.86f), (int)(b*0.86f), 0f, 0f, 1f);
					}
					if (!grid.isSolid(x - 1, y, z)) {
						quad(vc, e, glowLight, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, (int)(r*0.78f), (int)(g*0.78f), (int)(b*0.78f), -1f, 0f, 0f);
					}
					if (!grid.isSolid(x + 1, y, z)) {
						quad(vc, e, glowLight, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, (int)(r*0.78f), (int)(g*0.78f), (int)(b*0.78f), 1f, 0f, 0f);
					}
				}
			}
		}

		if (state.haft.hafted) {
			VoxelRockGrid.GeometryMetrics m = state.metrics();
			float cx = (float) (m.centerX() * cell);
			float cy = (float) (m.centerY() * cell);
			float cz = (float) (m.centerZ() * cell);
			matrices.push();
			matrices.translate(cx, cy, cz);
			if (state.haft.orientation == HaftData.Orientation.PERPENDICULAR_CHOP) {
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90f));
			} else if (state.haft.orientation == HaftData.Orientation.TRANSVERSE_ADZE) {
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(18f));
			} else if (state.haft.orientation == HaftData.Orientation.ANGLED_PICK) {
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(68f));
			}
			MatrixStack.Entry he = matrices.peek();
			float shaftLen = state.haft.orientation == HaftData.Orientation.INLINE ? 0.55f : 0.85f;
			drawBox(vc, he, light, -0.05f, -0.05f, 0.0f, 0.05f, 0.05f, shaftLen, 134, 96, 54);
			int[] bindRgb = switch (state.haft.bindingMaterial) {
				case "sinew" -> new int[]{220, 202, 166};
				case "tendon" -> new int[]{208, 186, 152};
				case "cordage" -> new int[]{162, 134, 88};
				default -> new int[]{106, 146, 68};
			};
			drawBox(vc, he, light, -0.075f, -0.075f, -0.06f, 0.075f, 0.075f, 0.14f,
					bindRgb[0], bindRgb[1], bindRgb[2]);
			matrices.pop();
		}
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}

	private static void drawBox(VertexConsumer vc, MatrixStack.Entry e, int light,
								float x0, float y0, float z0, float x1, float y1, float z1,
								int r, int g, int b) {
		quad(vc, e, light, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, 0f, 1f, 0f);
		quad(vc, e, light, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, (int)(r*0.7f), (int)(g*0.7f), (int)(b*0.7f), 0f, -1f, 0f);
		quad(vc, e, light, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, (int)(r*0.88f), (int)(g*0.88f), (int)(b*0.88f), 0f, 0f, -1f);
		quad(vc, e, light, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, (int)(r*0.88f), (int)(g*0.88f), (int)(b*0.88f), 0f, 0f, 1f);
		quad(vc, e, light, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, (int)(r*0.80f), (int)(g*0.80f), (int)(b*0.80f), -1f, 0f, 0f);
		quad(vc, e, light, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, (int)(r*0.80f), (int)(g*0.80f), (int)(b*0.80f), 1f, 0f, 0f);
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
