package com.projectrealism.metallurgy;

import net.minecraft.nbt.NbtCompound;

/**
 * Physical refractory crucible state containing solid or liquid alloy charges, surface floating slag,
 * dissolved gases, superheat-dependent viscosity, metal mixing, and high-temp carburization (crucible wootz steel).
 */
public final class CrucibleState {
	public AlloyComposition alloy = AlloyComposition.pureCopper();
	public double metalMassKg = 0.0;
	public double slagMassKg = 0.0;
	public double temperatureK = 293.15;
	/** Dissolved gas fraction (0..1); high superheat without degassing raises casting porosity. */
	public double dissolvedGas = 0.06;
	/** Solid organic carbon inside sealed crucible for wootz / crucible steel carburization. */
	public double enclosedCarbonKg = 0.0;

	public boolean isEmpty() {
		return metalMassKg < 0.01 && slagMassKg < 0.01;
	}

	public boolean isMolten() {
		return metalMassKg >= 0.01 && temperatureK >= alloy.liquidusTempK();
	}

	public boolean isPartiallyMolten() {
		return metalMassKg >= 0.01 && temperatureK >= alloy.solidusTempK();
	}

	/**
	 * Temperature-dependent kinematic fluidity (0 = frozen solid, 0.25 = pasty mush, 1.0+ = brisk free-flowing pour).
	 */
	public double fluidity() {
		if (metalMassKg < 0.01) return 0.0;
		double sol = alloy.solidusTempK();
		double liq = alloy.liquidusTempK();
		if (temperatureK < sol) return 0.0;
		if (temperatureK < liq) {
			return 0.08 + 0.22 * ((temperatureK - sol) / Math.max(10.0, liq - sol));
		}
		double superheat = temperatureK - liq;
		return Math.max(0.25, Math.min(1.65, (0.42 + superheat / 190.0) * alloy.fluidityFactor()));
	}

	public void addMetalCharge(AlloyComposition addedAlloy, double addedKg, double tempK) {
		if (addedKg <= 0.0) return;
		this.alloy = AlloyComposition.mix(this.alloy, this.metalMassKg, addedAlloy, addedKg);
		double total = this.metalMassKg + addedKg;
		this.temperatureK = (this.temperatureK * this.metalMassKg + tempK * addedKg) / total;
		this.metalMassKg = total;
		this.dissolvedGas = Math.min(0.65, this.dissolvedGas + 0.03);
	}

	public double skimSlag() {
		double s = slagMassKg;
		slagMassKg = 0.0;
		return s;
	}

	/** Advances heat exchange, slag float-separation, and crucible wootz carburization. */
	public void stepThermal(double envTempK, double dtSeconds) {
		if (dtSeconds <= 0.0) return;
		double cap = Math.max(450.0, 950.0 + metalMassKg * alloy.specificHeat());
		double hEff = envTempK > temperatureK ? 26.0 : 12.0;
		temperatureK += ((envTempK - temperatureK) * hEff / cap) * dtSeconds;

		// High-temperature molten steel absorbs enclosed carbon into uniform wootz charge!
		if (metalMassKg >= 0.02 && enclosedCarbonKg > 0.001 && alloy.fe >= alloy.cu && temperatureK >= 1380.0) {
			double rate = Math.min(enclosedCarbonKg, 0.0004 * dtSeconds * ((temperatureK - 1320.0) / 250.0));
			if (rate > 0.0 && alloy.c < 0.022) {
				double addC = Math.min(rate, metalMassKg * (0.020 - alloy.c));
				alloy = AlloyComposition.mix(alloy, metalMassKg,
						new AlloyComposition(0, 0, 0, 0, 1.0, 0, 0), addC);
				metalMassKg += addC;
				enclosedCarbonKg = Math.max(0.0, enclosedCarbonKg - addC);
			}
		}

		// High superheat above liquidus absorbs gases slightly unless cooled near pour window
		if (isMolten()) {
			double superheat = temperatureK - alloy.liquidusTempK();
			if (superheat > 180.0) {
				dissolvedGas = Math.min(0.60, dissolvedGas + 0.004 * dtSeconds);
			}
			// Slag floats upward cleanly while molten
			if (alloy.slag > 0.005) {
				double rise = Math.min(alloy.slag * 0.12, 0.012) * dtSeconds;
				double riseKg = metalMassKg * rise;
				slagMassKg += riseKg;
				alloy.slag = Math.max(0.0, alloy.slag - rise);
				alloy.normalize();
			}
		}
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.put("Alloy", alloy.toNbt());
		t.putDouble("MassKg", metalMassKg);
		t.putDouble("SlagKg", slagMassKg);
		t.putDouble("TempK", temperatureK);
		t.putDouble("Gas", dissolvedGas);
		t.putDouble("CarbKg", enclosedCarbonKg);
		return t;
	}

	public static CrucibleState fromNbt(NbtCompound t) {
		CrucibleState c = new CrucibleState();
		if (t.contains("Alloy")) c.alloy = AlloyComposition.fromNbt(t.getCompound("Alloy"));
		c.metalMassKg = t.getDouble("MassKg");
		c.slagMassKg = t.getDouble("SlagKg");
		c.temperatureK = t.contains("TempK") ? t.getDouble("TempK") : 293.15;
		c.dissolvedGas = t.getDouble("Gas");
		c.enclosedCarbonKg = t.getDouble("CarbKg");
		return c;
	}
}
