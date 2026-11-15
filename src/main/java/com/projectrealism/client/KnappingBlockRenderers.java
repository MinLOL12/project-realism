package com.projectrealism.client;

import com.projectrealism.ProjectRealism;
import com.projectrealism.knapping.DynamicKnappingBlocks;
import com.projectrealism.knapping.DynamicKnappingItems;
import com.projectrealism.knapping.DynamicStoneItem;
import com.projectrealism.knapping.DynamicStoneState;
import com.projectrealism.knapping.FractureEngine;
import com.projectrealism.knapping.HaftData;
import com.projectrealism.knapping.KnappingStoneBlock;
import com.projectrealism.knapping.KnappingStoneBlockEntity;
import com.projectrealism.knapping.VoxelRockGrid;
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
 * Dynamic voxel renderer for both in-world {@link KnappingStoneBlockEntity} rocks (including a live
 * crosshair target-voxel highlight) and held/inventory {@link DynamicStoneItem}s via Fabric's
 * BuiltinItemRendererRegistry. Every removed voxel, crack seam, inclusion, and handle mount renders live.
 */
@Environment(EnvType.CLIENT)
public final class KnappingBlockRenderers {
	private static final Identifier CANVAS_TEX = ProjectRealism.id("textures/misc/material_canvas.png");

	private KnappingBlockRenderers() {
	}

	public static void register() {
		BlockEntityRendererFactories.register(DynamicKnappingBlocks.KNAPPING_STONE_ENTITY,
				ctx -> new KnappingStoneRenderer());

		BuiltinItemRendererRegistry.INSTANCE.register(DynamicKnappingItems.DYNAMIC_STONE,
				(stack, mode, matrices, consumers, light, overlay) -> {
					DynamicStoneState state = DynamicStoneItem.readState(stack);
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
					renderStoneGeometry(state, matrices, consumers, light, null);
					matrices.pop();
				});
	}

	private static class KnappingStoneRenderer implements BlockEntityRenderer<KnappingStoneBlockEntity> {
		@Override
		public void render(KnappingStoneBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			DynamicStoneState s = be.stone();
			Direction dir = be.getCachedState().get(KnappingStoneBlock.FACING);

			// Check if player crosshair targets this exact placed stone to highlight targeted voxel
			FractureEngine.VoxelHit aimHit = null;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player != null && mc.crosshairTarget instanceof BlockHitResult bhr
					&& bhr.getType() == HitResult.Type.BLOCK
					&& bhr.getBlockPos().equals(be.getPos())) {
				Vec3d eyeLocal = KnappingStoneBlock.worldToVoxelPoint(dir, be.getPos(), mc.player.getEyePos());
				Vec3d hitLocal = KnappingStoneBlock.worldToVoxelPoint(dir, be.getPos(), bhr.getPos());
				Vec3d rayDir = hitLocal.subtract(eyeLocal).normalize();
				Vec3d start = hitLocal.subtract(rayDir.multiply(0.18));
				aimHit = FractureEngine.raycastVoxel(s.grid, start, rayDir);
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

			renderStoneGeometry(s, matrices, consumers, light, aimHit);
			matrices.pop();
		}
	}

	private static void renderStoneGeometry(DynamicStoneState state, MatrixStack matrices,
											VertexConsumerProvider consumers, int light,
											FractureEngine.VoxelHit aimHit) {
		VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(CANVAS_TEX));
		MatrixStack.Entry e = matrices.peek();
		VoxelRockGrid grid = state.grid;

		int baseR = state.material.colorR;
		int baseG = state.material.colorG;
		int baseB = state.material.colorB;
		float cell = 1.0f / VoxelRockGrid.SIZE;

		for (int y = 0; y < VoxelRockGrid.SIZE; y++) {
			float y0 = y * cell, y1 = y0 + cell;
			for (int z = 0; z < VoxelRockGrid.SIZE; z++) {
				float z0 = z * cell, z1 = z0 + cell;
				for (int x = 0; x < VoxelRockGrid.SIZE; x++) {
					if (!grid.isSolid(x, y, z)) continue;
					float x0 = x * cell, x1 = x0 + cell;

					int crack = grid.getCrackLevel(x, y, z);
					boolean inc = grid.hasInclusion(x, y, z);
					boolean aimed = aimHit != null && aimHit.x() == x && aimHit.y() == y && aimHit.z() == z;

					int r = baseR, g = baseG, b = baseB;
					if (inc) {
						r = Math.min(255, r + 32);
						g = Math.min(255, g + 26);
						b = Math.min(255, b + 16);
					}
					if (crack > 6) {
						float dark = Math.min(0.45f, crack / 90.0f);
						r = Math.max(8, (int) (r * (1.0f - dark)));
						g = Math.max(8, (int) (g * (1.0f - dark)));
						b = Math.max(8, (int) (b * (1.0f - dark)));
					}
					// Micro-variation by voxel parity gives natural mineral grain
					int grain = ((x * 7 + y * 13 + z * 11) & 3) * 3 - 4;
					r = clamp255(r + grain);
					g = clamp255(g + grain);
					b = clamp255(b + grain);
					if (aimed) {
						r = clamp255(r + 70);
						g = clamp255(g + 75);
						b = clamp255(b + 30);
					}

					// Draw ONLY exposed faces adjacent to air
					if (!grid.isSolid(x, y + 1, z)) {
						quad(vc, e, light, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, 0f, 1f, 0f);
					}
					if (!grid.isSolid(x, y - 1, z)) {
						quad(vc, e, light, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, (int)(r*0.65f), (int)(g*0.65f), (int)(b*0.65f), 0f, -1f, 0f);
					}
					if (!grid.isSolid(x, y, z - 1)) {
						quad(vc, e, light, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, (int)(r*0.86f), (int)(g*0.86f), (int)(b*0.86f), 0f, 0f, -1f);
					}
					if (!grid.isSolid(x, y, z + 1)) {
						quad(vc, e, light, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, (int)(r*0.86f), (int)(g*0.86f), (int)(b*0.86f), 0f, 0f, 1f);
					}
					if (!grid.isSolid(x - 1, y, z)) {
						quad(vc, e, light, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, (int)(r*0.78f), (int)(g*0.78f), (int)(b*0.78f), -1f, 0f, 0f);
					}
					if (!grid.isSolid(x + 1, y, z)) {
						quad(vc, e, light, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, (int)(r*0.78f), (int)(g*0.78f), (int)(b*0.78f), 1f, 0f, 0f);
					}
				}
			}
		}

		// Render attached wooden handle & natural binding wrap when hafted!
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
			// Wooden shaft
			drawBox(vc, he, light, -0.05f, -0.05f, 0.0f, 0.05f, 0.05f, shaftLen, 134, 96, 54);
			// Binding lashing collar color reflects material (sinew cream, cordage tan, fiber green)
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
