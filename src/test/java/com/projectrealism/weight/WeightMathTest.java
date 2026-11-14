package com.projectrealism.weight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightMathTest {
	@Test
	void walkingSpeedScalesWithMultiplier() {
		assertEquals(4.317, Encumbrance.walkingSpeedMetersPerSecond(1.0), 1.0e-3);
		assertEquals(2.1585, Encumbrance.walkingSpeedMetersPerSecond(0.5), 1.0e-3);
	}

	@Test
	void overloadRatio() {
		assertEquals(0.0, Encumbrance.overloadRatio(30.0, 30.0), 1.0e-9);
		assertEquals(1.0, Encumbrance.overloadRatio(60.0, 30.0), 1.0e-9);
		assertEquals(0.0, Encumbrance.overloadRatio(10.0, 30.0), 1.0e-9);
	}

	@Test
	void reportFlagsOverload() {
		WeightReport report = new WeightReport(42.0, 30.0, 0.55, java.util.List.of());
		assertTrue(report.overloaded());
		assertEquals(12.0, report.overloadKg(), 1.0e-9);
		assertEquals(55.0, report.speedPercent(), 1.0e-9);

		WeightReport light = WeightReport.empty(30.0);
		assertFalse(light.overloaded());
		assertEquals(Encumbrance.Severity.COMFORTABLE, light.severity());
	}
}
