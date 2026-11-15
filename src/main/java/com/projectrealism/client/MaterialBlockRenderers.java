package com.projectrealism.client;

import com.projectrealism.ProjectRealism;
import com.projectrealism.material.CuttingSurfaceBlock;
import com.projectrealism.material.CuttingSurfaceBlockEntity;
import com.projectrealism.material.DryingRackBlock;
import com.projectrealism.material.DryingRackBlockEntity;
import com.projectrealism.material.HideSurface;
import com.projectrealism.material.MaterialAging;
import com.projectrealism.material.MaterialBlocks;
import com.projectrealism.material.MaterialItems;
import com.projectrealism.material.ScrapingFrameBlock;
import com.projectrealism.material.ScrapingFrameBlockEntity;
import com.projectrealism.material.SewingFrameBlock;
import com.projectrealism.material.SewingFrameBlockEntity;
import com.projectrealism.material.StorageRackBlockEntity;
import com.projectrealism.material.WaterskinConstruction;
import com.projectrealism.material.WorkbenchBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/**
 * Diegetic 3D spatial BlockEntityRenderers rendering dynamic hide scraping regions, moisture
 * darkening, drying contraction & wind sway, knife cut paths, sewing alignment/holes/stitches/seals,
 * and persistent spatial items across workbenches and storage racks.
 */
@Environment(EnvType.CLIENT)
public final class MaterialBlockRenderers {
	private static final Identifier CANVAS_TEX = ProjectRealism.id("textures/misc/material_canvas.png");

	private MaterialBlockRenderers() {
	}

	public static void register() {
		BlockEntityRendererFactories.register(MaterialBlocks.SCRAPING_FRAME_ENTITY,
				ctx -> new ScrapingRenderer());
		BlockEntityRendererFactories.register(MaterialBlocks.DRYING_RACK_ENTITY,
				ctx -> new DryingRenderer(ctx));
		BlockEntityRendererFactories.register(MaterialBlocks.CUTTING_SURFACE_ENTITY,
				ctx -> new CuttingRenderer());
		BlockEntityRendererFactories.register(MaterialBlocks.SEWING_FRAME_ENTITY,
				ctx -> new SewingRenderer());
		BlockEntityRendererFactories.register(MaterialBlocks.WOODEN_WORKBENCH_ENTITY,
				ctx -> new WorkbenchRenderer(ctx));
		BlockEntityRendererFactories.register(MaterialBlocks.STORAGE_RACK_ENTITY,
				ctx -> new StorageRackRenderer(ctx));
	}

	private static void rotateFacing(MatrixStack matrices, Direction dir) {
		matrices.translate(0.5, 0.0, 0.5);
		float yaw = switch (dir) {
			case SOUTH -> 180f;
			case WEST -> 90f;
			case EAST -> -90f;
			default -> 0f;
		};
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
		matrices.translate(-0.5, 0.0, -0.5);
	}

	private static void drawQuad(VertexConsumer vc, MatrixStack.Entry e, int light,
								 float x0, float y0, float z0,
								 float x1, float y1, float z1,
								 float x2, float y2, float z2,
								 float x3, float y3, float z3,
								 int r, int g, int b, int a,
								 float nx, float ny, float nz) {
		vc.vertex(e, x0, y0, z0).color(r, g, b, a).texture(0f, 0f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
		vc.vertex(e, x1, y1, z1).color(r, g, b, a).texture(0f, 1f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
		vc.vertex(e, x2, y2, z2).color(r, g, b, a).texture(1f, 1f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
		vc.vertex(e, x3, y3, z3).color(r, g, b, a).texture(1f, 0f)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
	}

	private static void drawHideGrid(HideSurface h, MatrixStack matrices, VertexConsumerProvider consumers,
									 int light, float width, float height, boolean showCuts) {
		VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(CANVAS_TEX));
		MatrixStack.Entry entry = matrices.peek();
		float shrink = (float) (1.0 - h.contractionFrac * 0.45);
		float cw = (width * shrink) / HideSurface.GRID;
		float ch = (height * shrink) / HideSurface.GRID;
		float ox = -0.5f * width * shrink;
		float oy = -0.5f * height * shrink;

		for (int gy = 0; gy < HideSurface.GRID; gy++) {
			for (int gx = 0; gx < HideSurface.GRID; gx++) {
				int ci = HideSurface.idx(gx, gy);
				if (!h.intact[ci]) continue;

				double clean = h.fleshClean[ci];
				double moist = h.moisture[ci];
				double dmg = h.damage[ci];
				double scorch = h.scorch[ci];

				// Interpolate fleshy raw reddish-brown -> clean supple tan, darkened by moisture & scorch
				float r = (float) (176 + (206 - 176) * clean);
				float g = (float) (88 + (152 - 88) * clean);
				float b = (float) (72 + (98 - 72) * clean);
				float wetDark = (float) (1.0 - moist * 0.42 - scorch * 0.60 - h.mildewDiscoloration * 0.25);
				r = Math.max(25f, Math.min(255f, r * wetDark - (float) (dmg * 55.0)));
				g = Math.max(20f, Math.min(255f, g * wetDark - (float) (dmg * 45.0)));
				b = Math.max(18f, Math.min(255f, b * wetDark - (float) (dmg * 40.0)));

				if (showCuts && h.cutMark[ci]) {
					r = 245f;
					g = 235f;
					b = 140f; // bright visible knife score line!
				}

				float x0 = ox + gx * cw;
				float y0 = oy + (HideSurface.GRID - 1 - gy) * ch;
				drawQuad(vc, entry, light,
						x0, y0, 0f,
						x0 + cw, y0, 0f,
						x0 + cw, y0 + ch, 0f,
						x0, y0 + ch, 0f,
						(int) r, (int) g, (int) b, 255,
						0f, 0f, 1f);
			}
		}
	}

	// ---------------------------------------------------- 1. Scraping Frame ---
	private static class ScrapingRenderer implements BlockEntityRenderer<ScrapingFrameBlockEntity> {
		@Override
		public void render(ScrapingFrameBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			HideSurface h = be.hide();
			if (h == null) return;
			matrices.push();
			rotateFacing(matrices, be.getCachedState().get(ScrapingFrameBlock.FACING));
			matrices.translate(0.5, 0.56, 0.52);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-24f));
			drawHideGrid(h, matrices, consumers, light, 0.72f, 0.68f, false);
			matrices.pop();
		}
	}

	// ------------------------------------------------------- 2. Drying Rack ---
	private static class DryingRenderer implements BlockEntityRenderer<DryingRackBlockEntity> {
		private final ItemRenderer itemRenderer;

		DryingRenderer(BlockEntityRendererFactory.Context ctx) {
			this.itemRenderer = ctx.getItemRenderer();
		}

		@Override
		public void render(DryingRackBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			HideSurface h = be.hide();
			if (h != null) {
				matrices.push();
				rotateFacing(matrices, be.getCachedState().get(DryingRackBlock.FACING));
				long time = be.getWorld() != null ? be.getWorld().getTime() : 0L;
				// Gentle airflow sway damped by corner tension
				double avgT = (h.cornerTension[0] + h.cornerTension[1] + h.cornerTension[2] + h.cornerTension[3]) * 0.25;
				float sway = (float) (Math.sin((time + tickDelta) * 0.08) * (3.5 - avgT * 2.5));
				matrices.translate(0.5, 0.55, 0.50);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(sway));
				drawHideGrid(h, matrices, consumers, light, 0.70f, 0.68f, false);

				// Render 4 corner tension cords
				VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(CANVAS_TEX));
				MatrixStack.Entry entry = matrices.peek();
				for (int c = 0; c < 4; c++) {
					float sx = (c == 0 || c == 3) ? -0.36f : 0.36f;
					float sy = (c < 2) ? 0.36f : -0.36f;
					int col = h.cornerTension[c] > 0.65 ? 210 : 145;
					drawQuad(vc, entry, light,
							sx - 0.02f, sy - 0.02f, 0.01f,
							sx + 0.02f, sy - 0.02f, 0.01f,
							sx + 0.02f, sy + 0.02f, 0.01f,
							sx - 0.02f, sy + 0.02f, 0.01f,
							col, 175, 110, 255, 0f, 0f, 1f);
				}
				matrices.pop();
				return;
			}
			MaterialAging.GourdState gs = be.gourd();
			if (gs != null) {
				matrices.push();
				matrices.translate(0.5, 0.62, 0.5);
				matrices.scale(0.55f, 0.55f, 0.55f);
				itemRenderer.renderItem(new ItemStack(MaterialItems.RAW_GOURD),
						ModelTransformationMode.FIXED, light, overlay, matrices, consumers, be.getWorld(), 0);
				matrices.pop();
			}
		}
	}

	// --------------------------------------------------- 3. Cutting Surface ---
	private static class CuttingRenderer implements BlockEntityRenderer<CuttingSurfaceBlockEntity> {
		@Override
		public void render(CuttingSurfaceBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			HideSurface h = be.hide();
			if (h == null) return;
			matrices.push();
			matrices.translate(0.5, 0.765, 0.5);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f));
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(be.rotationSteps() * 90f));
			drawHideGrid(h, matrices, consumers, light, 0.78f, 0.78f, true);
			matrices.pop();
		}
	}

	// ------------------------------------------------------ 4. Sewing Frame ---
	private static class SewingRenderer implements BlockEntityRenderer<SewingFrameBlockEntity> {
		@Override
		public void render(SewingFrameBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			if (!be.hasPendingPiece() && !be.hasConstruction()) return;
			matrices.push();
			rotateFacing(matrices, be.getCachedState().get(SewingFrameBlock.FACING));
			VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(CANVAS_TEX));
			MatrixStack.Entry entry = matrices.peek();

			if (be.hasPendingPiece()) {
				drawQuad(vc, entry, light,
						0.25f, 0.28f, 0.48f,
						0.75f, 0.28f, 0.48f,
						0.75f, 0.80f, 0.48f,
						0.25f, 0.80f, 0.48f,
						172, 114, 68, 255, 0f, 0f, 1f);
				matrices.pop();
				return;
			}

			WaterskinConstruction c = be.construction();
			if (c != null) {
				float off = (float) be.alignOffset() * 0.22f;
				// Back leather piece
				drawQuad(vc, entry, light,
						0.26f, 0.26f, 0.46f,
						0.74f, 0.26f, 0.46f,
						0.74f, 0.82f, 0.46f,
						0.26f, 0.82f, 0.46f,
						145, 92, 54, 255, 0f, 0f, -1f);
				// Front leather piece visibly offset until aligned!
				drawQuad(vc, entry, light,
						0.26f + off, 0.26f, 0.54f,
						0.74f + off, 0.26f, 0.54f,
						0.74f + off, 0.82f, 0.54f,
						0.26f + off, 0.82f, 0.54f,
						178, 118, 70, 255, 0f, 0f, 1f);

				// Render punched holes, cordage stitches, and golden wax seal beads along all 4 seams!
				for (WaterskinConstruction.SeamZone z : WaterskinConstruction.SeamZone.values()) {
					WaterskinConstruction.SeamSection sec = c.seams[z.index];
					for (int i = 0; i < sec.punchedHoles; i++) {
						float t = (i + 0.5f) / WaterskinConstruction.HOLES_PER_SEAM;
						float hx, hy;
						switch (z) {
							case TOP_NECK -> { hx = 0.30f + t * 0.40f; hy = 0.79f; }
							case BOTTOM_GUSSET -> { hx = 0.30f + t * 0.40f; hy = 0.29f; }
							case LEFT_FLANK -> { hx = 0.29f; hy = 0.32f + t * 0.44f; }
							default -> { hx = 0.71f; hy = 0.32f + t * 0.44f; }
						}
						boolean stitched = i < sec.stitchedHoles;
						int r = sec.sealLevel > 0.4 ? 235 : (stitched ? 215 : 35);
						int g = sec.sealLevel > 0.4 ? 198 : (stitched ? 195 : 28);
						int b = sec.sealLevel > 0.4 ? 85 : (stitched ? 140 : 25);
						float sz = stitched ? 0.018f : 0.013f;
						drawQuad(vc, entry, light,
								hx - sz, hy - sz, 0.55f,
								hx + sz, hy - sz, 0.55f,
								hx + sz, hy + sz, 0.55f,
								hx - sz, hy + sz, 0.55f,
								r, g, b, 255, 0f, 0f, 1f);
					}
				}
			}
			matrices.pop();
		}
	}

	// -------------------------------------------------- 5. Wooden Workbench ---
	private static class WorkbenchRenderer implements BlockEntityRenderer<WorkbenchBlockEntity> {
		private final ItemRenderer itemRenderer;

		WorkbenchRenderer(BlockEntityRendererFactory.Context ctx) {
			this.itemRenderer = ctx.getItemRenderer();
		}

		@Override
		public void render(WorkbenchBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			for (int s = 0; s < WorkbenchBlockEntity.SLOTS; s++) {
				ItemStack st = be.getSlot(s);
				if (st.isEmpty()) continue;
				float qx = (s % 2 == 0) ? 0.28f : 0.72f;
				float qz = (s < 2) ? 0.28f : 0.72f;
				matrices.push();
				matrices.translate(qx, 0.96, qz);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
				matrices.scale(0.36f, 0.36f, 0.36f);
				itemRenderer.renderItem(st, ModelTransformationMode.FIXED, light, overlay,
						matrices, consumers, be.getWorld(), s);
				matrices.pop();
			}
		}
	}

	// ------------------------------------------------------ 6. Storage Rack ---
	private static class StorageRackRenderer implements BlockEntityRenderer<StorageRackBlockEntity> {
		private final ItemRenderer itemRenderer;

		StorageRackRenderer(BlockEntityRendererFactory.Context ctx) {
			this.itemRenderer = ctx.getItemRenderer();
		}

		@Override
		public void render(StorageRackBlockEntity be, float tickDelta, MatrixStack matrices,
						   VertexConsumerProvider consumers, int light, int overlay) {
			for (int s = 0; s < StorageRackBlockEntity.SLOTS; s++) {
				ItemStack st = be.getSlot(s);
				if (st.isEmpty()) continue;
				float qx = (s % 2 == 0) ? 0.30f : 0.70f;
				float qy = (s < 2) ? 0.34f : 0.76f;
				matrices.push();
				matrices.translate(qx, qy, 0.50);
				matrices.scale(0.42f, 0.42f, 0.42f);
				itemRenderer.renderItem(st, ModelTransformationMode.FIXED, light, overlay,
						matrices, consumers, be.getWorld(), s);
				matrices.pop();
			}
		}
	}
}
