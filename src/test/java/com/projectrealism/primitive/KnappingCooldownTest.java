package com.projectrealism.primitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnappingCooldownTest {
	@Test
	@DisplayName("separate client and server strike maps do not block singleplayer strikes")
	void clientAndServerMapsAreIndependent() {
		Map<UUID, Long> clientMap = new ConcurrentHashMap<>();
		Map<UUID, Long> serverMap = new ConcurrentHashMap<>();
		UUID player = UUID.randomUUID();

		long t0 = 10_000L;
		assertTrue(Knapping.tryConsumeCooldown(clientMap, player, t0, Knapping.CLIENT_COOLDOWN_MS));
		// Integrated server receives the packet 1 ms later: must still succeed.
		assertTrue(Knapping.tryConsumeCooldown(serverMap, player, t0 + 1L, Knapping.SERVER_COOLDOWN_MS));

		// Rapid repeat click within cooldown is blocked on both sides.
		assertFalse(Knapping.tryConsumeCooldown(clientMap, player, t0 + 200L, Knapping.CLIENT_COOLDOWN_MS));
		assertFalse(Knapping.tryConsumeCooldown(serverMap, player, t0 + 201L, Knapping.SERVER_COOLDOWN_MS));

		// Server cooldown is shorter than client cooldown so network jitter never drops a valid strike.
		assertTrue(Knapping.SERVER_COOLDOWN_MS < Knapping.CLIENT_COOLDOWN_MS);
		assertTrue(Knapping.tryConsumeCooldown(clientMap, player, t0 + Knapping.CLIENT_COOLDOWN_MS,
				Knapping.CLIENT_COOLDOWN_MS));
		assertTrue(Knapping.tryConsumeCooldown(serverMap, player, t0 + Knapping.CLIENT_COOLDOWN_MS - 10L,
				Knapping.SERVER_COOLDOWN_MS));
	}
}
