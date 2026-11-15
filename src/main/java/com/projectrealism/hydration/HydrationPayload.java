package com.projectrealism.hydration;

import com.projectrealism.ProjectRealism;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Server to client physiological hydration state. Sends perceived thirst and bodily cues
 * (sweating, body temperature, stomach fullness, dark urine cue, electrolyte imbalance) rather
 * than a raw total-body-water percentage.
 */
public record HydrationPayload(
		float perceivedThirst,
		float coreTempC,
		float sweatRateGMin,
		float sweatDripFraction,
		float stomachFullness,
		float urineOsmolality,
		byte statusFlags, // bit 0: hyponatremia, bit 1: hypernatremia, bit 2: k-imbalance, bit 3: gut distress
		boolean visible
) implements CustomPayload {
	public static final Id<HydrationPayload> ID = new Id<>(ProjectRealism.id("hydration_update"));

	public static final PacketCodec<RegistryByteBuf, HydrationPayload> CODEC = PacketCodec.of(
			(value, buf) -> {
				buf.writeFloat(value.perceivedThirst);
				buf.writeFloat(value.coreTempC);
				buf.writeFloat(value.sweatRateGMin);
				buf.writeFloat(value.sweatDripFraction);
				buf.writeFloat(value.stomachFullness);
				buf.writeFloat(value.urineOsmolality);
				buf.writeByte(value.statusFlags);
				buf.writeBoolean(value.visible);
			},
			buf -> new HydrationPayload(
					buf.readFloat(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readByte(),
					buf.readBoolean()
			)
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
