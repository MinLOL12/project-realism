package com.projectrealism.weight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncumbranceTest {
	private static final double CAPACITY = 30.0;
	private static final double HALF_LIFE = 20.0;
	private static final double EXPONENT = 1.25;
	private static final double FLOOR = 0.12;

	private static double speed(double kg) {
		return Encumbrance.speedMultiplier(kg, CAPACITY, HALF_LIFE, EXPONENT, FLOOR);
	}

	@Test
	@DisplayName("anything at or below capacity costs nothing")
	void underCapacityIsFree() {
		assertEquals(1.0, speed(0.0), 1.0e-9);
		assertEquals(1.0, speed(17.5), 1.0e-9);
		assertEquals(1.0, speed(CAPACITY), 1.0e-9);
	}

	@Test
	@DisplayName("the curve is continuous — one extra kilogram is a nudge, not a cliff")
	void noCliffAtCapacity() {
		double justOver = speed(CAPACITY + 0.1);
		assertTrue(justOver > 0.99, "0.1 kg over capacity should barely register, was " + justOver);
		assertTrue(justOver < 1.0);
	}

	@Test
	@DisplayName("penalty is monotonic: more weight is never faster")
	void monotonic() {
		double previous = 1.0;
		for (double kg = 0; kg <= 200; kg += 0.5) {
			double current = speed(kg);
			assertTrue(current <= previous + 1.0e-9, "speed rose at " + kg + " kg");
			previous = current;
		}
	}

	@Test
	@DisplayName("halfLifeKg halves speed once you are that far over capacity")
	void halfLifeHalvesSpeed() {
		// 1 / (1 + 1^exponent) = 0.5 regardless of the exponent, which is the point of the shape.
		assertEquals(0.5, speed(CAPACITY + HALF_LIFE), 1.0e-9);
	}

	@Test
	@DisplayName("absurd loads never fully immobilise the player")
	void respectsFloor() {
		assertEquals(FLOOR, speed(10_000.0), 1.0e-9);
	}

	@Test
	@DisplayName("the worked example: 4 iron blocks is a crawl")
	void ironBlocks() {
		double kg = 4 * 40.0;
		double multiplier = speed(kg);
		// 130 kg is 100 kg over: 1 / (1 + 5^1.25) ≈ 0.12, i.e. the floor.
		assertTrue(multiplier <= 0.2, "carrying 160 kg should be near-immobile, was " + multiplier);
	}

	@Test
	@DisplayName("a modest mining trip is only mildly punishing")
	void miningTrip() {
		// 2 iron blocks (80) worth of ore plus a pickaxe — well over capacity, but survivable.
		double kg = 80 + 4.0;
		double multiplier = speed(kg);
		assertTrue(multiplier > 0.1 && multiplier < 0.35, "expected a slow trudge, got " + multiplier);
	}

	@Test
	@DisplayName("severity bands line up with the multipliers")
	void bands() {
		assertEquals(Encumbrance.Severity.COMFORTABLE, Encumbrance.severity(30.0, CAPACITY));
		assertEquals(Encumbrance.Severity.LOADED, Encumbrance.severity(33.0, CAPACITY));
		assertEquals(Encumbrance.Severity.STRAINED, Encumbrance.severity(45.0, CAPACITY));
		assertEquals(Encumbrance.Severity.CRUSHED, Encumbrance.severity(60.0, CAPACITY));
	}

	@Test
	@DisplayName("standing still under a load still costs hunger, but less than walking")
	void exhaustion() {
		float walking = Encumbrance.exhaustionPerTick(50.0, CAPACITY, 0.01, true);
		float standing = Encumbrance.exhaustionPerTick(50.0, CAPACITY, 0.01, false);
		assertTrue(walking > standing);
		assertTrue(standing > 0.0f);
		assertEquals(0.0f, Encumbrance.exhaustionPerTick(20.0, CAPACITY, 0.01, true));
	}
}
