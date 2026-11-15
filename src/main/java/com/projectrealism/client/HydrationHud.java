package com.projectrealism.client;

import com.projectrealism.hydration.HydrationPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * Physiological hydration & thermoregulation HUD rendered above the right side of the hotbar.
 * Displays perceived thirst, body temperature, sweating/dripping state, stomach fullness, and
 * electrolyte/urine cues without exposing exact body-water percentage.
 */
@Environment(EnvType.CLIENT)
public class HydrationHud implements HudRenderCallback {
	private static volatile HydrationPayload latest = null;
	private static long lastUpdateMs = 0L;
	private static final long STALE_AFTER_MS = 2500L;

	public static void update(HydrationPayload payload) {
		latest = payload;
		lastUpdateMs = System.currentTimeMillis();
	}

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		HydrationPayload p = latest;
		if (p == null || !p.visible()) {
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

		// Sit cleanly above food bar (and shift up if underwater air bubbles are visible)
		int barWidth = 81;
		int barX = width / 2 + 10;
		int yOffset = client.player.getAir() < client.player.getMaxAir() ? 60 : 50;
		int barY = height - yOffset;

		float thirst = Math.max(0.0f, Math.min(1.0f, p.perceivedThirst()));
		float hydrationFeel = 1.0f - thirst;

		int thirstColor;
		String sensation;
		if (thirst < 0.14f) {
			thirstColor = 0xFF44BBFF;
			sensation = "Quenched";
		} else if (thirst < 0.35f) {
			thirstColor = 0xFF77DDAA;
			sensation = "Mild Thirst";
		} else if (thirst < 0.62f) {
			thirstColor = 0xFFFFCC44;
			sensation = "Thirsty";
		} else if (thirst < 0.85f) {
			thirstColor = 0xFFFF8833;
			sensation = "Parched";
		} else {
			thirstColor = 0xFFFF4444;
			sensation = "Desperate";
		}

		// Perceived hydration / thirst bar
		context.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 4, 0x88000000);
		int filled = Math.round(hydrationFeel * barWidth);
		context.fill(barX, barY, barX + filled, barY + 3, thirstColor);

		// Compact status text above bar: "Thirsty  37.2°C"
		int tempColor = p.coreTempC() >= 38.6f ? 0xFFFF6644
				: (p.coreTempC() <= 35.5f ? 0xFF66DDFF : 0xFFE0E0E0);
		Text mainLine = Text.literal(String.format("%s  %.1f°C", sensation, p.coreTempC()));
		context.drawTextWithShadow(client.textRenderer, mainLine, barX, barY - 10, thirstColor);

		// Secondary line (sweat / drip / stomach / electrolyte warning)
		StringBuilder sub = new StringBuilder();
		if (p.sweatRateGMin() >= 2.5f) {
			sub.append(p.sweatDripFraction() > 0.45f ? "Dripping Sweat" : "Sweating");
		}
		if (p.stomachFullness() > 0.65f) {
			if (!sub.isEmpty()) sub.append(" · ");
			sub.append("Full Gut");
		}
		if ((p.statusFlags() & 1) != 0) {
			if (!sub.isEmpty()) sub.append(" · ");
			sub.append("Low Na+");
		} else if ((p.statusFlags() & 2) != 0) {
			if (!sub.isEmpty()) sub.append(" · ");
			sub.append("High Na+");
		} else if ((p.statusFlags() & 4) != 0) {
			if (!sub.isEmpty()) sub.append(" · ");
			sub.append("K+ Cramps");
		} else if ((p.statusFlags() & 8) != 0) {
			if (!sub.isEmpty()) sub.append(" · ");
			sub.append("Gut Illness");
		} else if (p.urineOsmolality() >= 820f && sub.isEmpty()) {
			sub.append("Dark Urine");
		}

		if (!sub.isEmpty()) {
			int subColor = (p.statusFlags() != 0 || p.sweatDripFraction() > 0.55f) ? 0xFFFFAA55 : tempColor;
			context.drawTextWithShadow(client.textRenderer, Text.literal(sub.toString()), barX, barY - 19, subColor);
		}
	}
}
