package com.projectrealism.weight;

import com.projectrealism.ProjectRealism;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Server to client: "you are hauling this much".
 *
 * <p>The client cannot weigh the inventory itself — it does not know the server's weight table or its
 * config — so the numbers are pushed from the authoritative side. Rendering is then free every frame
 * instead of recomputing an inventory every frame.</p>
 */
public record WeightPayload(float weightKg, float capacityKg, float speedMultiplier, boolean visible)
		implements CustomPayload {
	public static final Id<WeightPayload> ID = new Id<>(ProjectRealism.id("weight_update"));

	public static final PacketCodec<RegistryByteBuf, WeightPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.FLOAT, WeightPayload::weightKg,
			PacketCodecs.FLOAT, WeightPayload::capacityKg,
			PacketCodecs.FLOAT, WeightPayload::speedMultiplier,
			PacketCodecs.BOOL, WeightPayload::visible,
			WeightPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
