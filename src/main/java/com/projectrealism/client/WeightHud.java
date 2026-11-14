package com.projectrealism.client;

import com.projectrealism.weight.Encumbrance;
import com.projectrealism.weight.WeightPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * The weight readout: "23.4 / 30 kg", plus the current speed penalty, sitting just above the hotbar.
 *
 * <p>Colour carries the meaning: white when free, yellow when loaded, orange when every step costs
 * something, red when the player is one block away from not moving at all.</p>
 */
@Environment(EnvType.CLIENT)
public class WeightHud implements HudRenderCallback {
	private static volatile WeightPayload latest = null;
	private static long lastUpdateMs = 0L;

	/** If the server stops sending updates (dimension change, lag spike), fade the readout out. */
	private static final long STALE_AFTER_MS = 1500L;

	public static void update(WeightPayload payload) {
		latest = payload;
		lastUpdateMs = System.currentTimeMillis();
	}

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		WeightPayload payload = latest;
		if (payload == null || !payload.visible()) {
			return;
		}
		if (System.currentTimeMillis() - lastUpdateMs > STALE_AFTER_MS) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden) {
			return;
		}

		int width = context.getScaledWindowWidth();
		int height = context.getScaledWindowHeight();

		String weightText = String.format("%.1f / %.0f kg", payload.weightKg(), payload.capacityKg());
		Text label = Text.literal(weightText);

		int color = switch (Encumbrance.severity(payload.weightKg(), payload.capacityKg())) {
			case COMFORTABLE -> 0xFFFFFFFF;
			case LOADED -> 0xFFFFE066;
			case STRAINED -> 0xFFFF9933;
			case CRUSHED -> 0xFFFF5555;
		};

		int x = width / 2 - client.textRenderer.getWidth(label) / 2;
		int y = height - 55;

		context.drawTextWithShadow(client.textRenderer, label, x, y, color);

		if (payload.speedMultiplier() < 0.999f) {
			Text penalty = Text.literal(String.format("-%d%% speed", Math.round((1.0f - payload.speedMultiplier()) * 100f)));
			int penaltyX = width / 2 - client.textRenderer.getWidth(penalty) / 2;
			context.drawTextWithShadow(client.textRenderer, penalty, penaltyX, y - 10, 0xFFAAAAAA);
		}

		// A thin bar under the text keeps the overload readable at a glance without another number.
		int barWidth = 82;
		int barX = width / 2 - barWidth / 2;
		int barY = y + 10;
		double ratio = payload.capacityKg() <= 0f ? 0.0 : payload.weightKg() / payload.capacityKg();
		double filled = Math.min(1.0, ratio) * barWidth;
		context.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 3, 0x88000000);
		context.fill(barX, barY, barX + (int) Math.round(filled), barY + 2, color);
		if (ratio > 1.0) {
			// Overflow is drawn as a second, brighter segment so "over capacity" is unmistakable.
			int overflow = (int) Math.round(Math.min(1.0, ratio - 1.0) * barWidth);
			context.fill(barX, barY + 3, barX + overflow, barY + 4, 0xFFFF3030);
		}
	}
}
