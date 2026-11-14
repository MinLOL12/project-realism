package com.projectrealism.primitive;

import com.projectrealism.RealismConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolConditionMathTest {
	@Test
	@DisplayName("dull tools chop significantly slower than freshly sharpened tools")
	void sharpnessSpeedScalesMonotonically() {
		float sharp = ToolCondition.speedMultiplierForSharpness(100);
		float worn = ToolCondition.speedMultiplierForSharpness(50);
		float dull = ToolCondition.speedMultiplierForSharpness(0);

		assertEquals(1.0f, sharp, 1.0e-5f);
		assertTrue(worn > dull && worn < sharp);
		assertEquals(0.25f, dull, 1.0e-5f);

		assertFalse(ToolCondition.isDullSharpness(100));
		assertFalse(ToolCondition.isDullSharpness(35));
		assertTrue(ToolCondition.isDullSharpness(34));
		assertTrue(ToolCondition.isDullSharpness(0));
	}

	@Test
	@DisplayName("new wood and tool degradation config flags validate and clamp cleanly")
	void configDefaultsAndDrySpeedClamp() {
		RealismConfig cfg = new RealismConfig();
		assertTrue(cfg.physicalTreeChopping);
		assertTrue(cfg.logProcessing);
		assertTrue(cfg.toolDegradation);
		assertEquals(1.0, cfg.woodDrySpeedMultiplier, 1.0e-6);

		cfg.woodDrySpeedMultiplier = 0.01;
		cfg.validate();
		assertEquals(0.1, cfg.woodDrySpeedMultiplier, 1.0e-6);

		cfg.woodDrySpeedMultiplier = 99.0;
		cfg.validate();
		assertEquals(20.0, cfg.woodDrySpeedMultiplier, 1.0e-6);
	}
}
