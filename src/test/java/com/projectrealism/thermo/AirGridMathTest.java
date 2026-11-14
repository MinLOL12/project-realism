package com.projectrealism.thermo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AirGridMathTest {

	@Test
	@DisplayName("unvented enclosed shelter loses oxygen and traps rising smoke/CO under roof unlike chimneyed room")
	void sealedRoomVsChimneyedShelter() {
		AirGrid sealed = new AirGrid();
		AirGrid vented = new AirGrid();

		// Indoor 3x2x3 volume: x in [0..2], y in [0..1], z in [0..2]. Walls/roof at boundary are impermeable (0.0).
		AirGrid.GeometrySampler sealedGeo = new AirGrid.GeometrySampler() {
			@Override
			public double cellOpenness(AirGrid.CellPos c) {
				return (c.x() >= 0 && c.x() <= 2 && c.y() >= 0 && c.y() <= 1 && c.z() >= 0 && c.z() <= 2) ? 1.0 : 0.0;
			}

			@Override
			public boolean isOutdoorVented(AirGrid.CellPos c) {
				return false;
			}
		};

		// Same room but with an open chimney shaft at (1, 2..3, 1) and low side air intake at (0, 0, -1)
		AirGrid.GeometrySampler ventedGeo = new AirGrid.GeometrySampler() {
			@Override
			public double cellOpenness(AirGrid.CellPos c) {
				if (c.x() >= 0 && c.x() <= 2 && c.y() >= 0 && c.y() <= 1 && c.z() >= 0 && c.z() <= 2) {
					return 1.0;
				}
				if (c.x() == 1 && c.z() == 1 && c.y() >= 2 && c.y() <= 4) {
					return 1.0; // chimney flue
				}
				if (c.x() == 0 && c.y() == 0 && c.z() == -1) {
					return 1.0; // draft intake
				}
				return 0.0;
			}

			@Override
			public boolean isOutdoorVented(AirGrid.CellPos c) {
				return (c.x() == 1 && c.z() == 1 && c.y() >= 3) || (c.x() == 0 && c.y() == 0 && c.z() == -1);
			}
		};

		AirGrid.CellPos hearth = new AirGrid.CellPos(1, 0, 1);
		AirGrid.CellPos ceiling = new AirGrid.CellPos(1, 1, 1);
		double amb = ThermalConstants.AMBIENT_DEFAULT_K;

		sealed.activateRadius(hearth, 1, amb, 1L, sealedGeo);
		vented.activateRadius(hearth, 2, amb, 1L, ventedGeo);

		for (int step = 0; step < 180; step++) {
			long tick = step * 10L;
			sealed.injectCombustion(hearth, amb, tick, 12000.0, 0.10, 0.12, 0.025, 0.6, 0.0, 0.5);
			vented.injectCombustion(hearth, amb, tick, 12000.0, 0.10, 0.12, 0.025, 0.6, 0.0, 0.5);
			sealed.step(0.5, amb, 0.5, 0.2, tick, sealedGeo);
			vented.step(0.5, amb, 0.5, 0.2, tick, ventedGeo);
		}

		AirGrid.AirCell sealedHearth = sealed.getReadOnly(hearth, amb);
		AirGrid.AirCell sealedRoof = sealed.getReadOnly(ceiling, amb);
		AirGrid.AirCell ventedHearth = vented.getReadOnly(hearth, amb);
		AirGrid.AirCell ventedRoof = vented.getReadOnly(ceiling, amb);

		// Sealed room becomes hypoxic, smoky, and CO-laden at ceiling
		assertTrue(sealedHearth.o2 < 0.165);
		assertTrue(sealedRoof.smoke > ventedRoof.smoke + 0.15);
		assertTrue(sealedRoof.co > ventedRoof.co);
		// Chimneyed room draws fresh air & maintains healthier oxygen while venting upward
		assertTrue(ventedHearth.o2 > sealedHearth.o2 + 0.015);
		assertTrue(ventedRoof.vy > 0.05);
	}
}
