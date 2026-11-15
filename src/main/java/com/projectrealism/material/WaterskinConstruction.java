package com.projectrealism.material;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * Persistent multi-part construction, per-seam integrity, hydrostatic leak physics, and flexible
 * fluid-body slosh model for leather waterskins. Two waterskins crafted from the same hide by
 * different players remain physically distinct items with unique seam histories, capacities, and weights.
 */
public final class WaterskinConstruction {
	public static final int SEAM_COUNT = 4;
	public static final int HOLES_PER_SEAM = 6;

	public enum SeamZone {
		TOP_NECK(0, "top_neck", 0.72),
		LEFT_FLANK(1, "left_flank", 0.22),
		RIGHT_FLANK(2, "right_flank", 0.22),
		BOTTOM_GUSSET(3, "bottom_gusset", 0.00);

		public final int index;
		public final String key;
		public final double heightFraction; // normalized height of seam on vertical container

		SeamZone(int index, String key, double heightFraction) {
			this.index = index;
			this.key = key;
			this.heightFraction = heightFraction;
		}
	}

	public static final class SeamSection {
		public double edgeAlignment = 1.0;   // 0..1 alignment accuracy when pieces were clamped
		public double cutQuality = 1.0;      // 0..1 inherited knife edge smoothness
		public int punchedHoles = 0;         // 0..6 awl holes
		public int stitchedHoles = 0;        // 0..punchedHoles sewn stitches
		public double meanTension = 0.0;     // 0..1 thread draw tension (0.68..0.84 ideal)
		public double tearDamage = 0.0;      // 0..1 hole tearing from excessive tension
		public double sealLevel = 0.0;       // 0..1 beeswax/tallow/resin waterproofing
		public double seamWear = 0.0;        // 0..1 mechanical flex & cyclic moisture fatigue

		/** Effective structural closure of this seam (0 = gaping wide, 1 = tight flush). */
		public double stitchClosure() {
			if (punchedHoles <= 0 || stitchedHoles <= 0) {
				return 0.0;
			}
			double coverage = stitchedHoles / (double) HOLES_PER_SEAM;
			// Too loose (<0.5) leaves visible gaps; >0.88 starts tearing leather holes open
			double tensionFit = meanTension < 0.65
					? Math.max(0.15, meanTension / 0.65)
					: (meanTension <= 0.88 ? 1.0 : Math.max(0.45, 1.0 - (meanTension - 0.88) * 3.5));
			double raw = coverage * tensionFit * (0.45 + 0.55 * edgeAlignment) * (0.55 + 0.45 * cutQuality);
			return Math.max(0.0, Math.min(1.0, raw * (1.0 - tearDamage * 0.85) * (1.0 - seamWear * 0.65)));
		}

		/** Combined watertight integrity (0 = porous/open, 1 = completely watertight). */
		public double integrity() {
			double closure = stitchClosure();
			if (closure <= 0.05) return 0.0;
			// Even tight stitches weep slowly through needle holes unless sealed with wax/resin/tallow!
			return Math.max(0.0, Math.min(1.0, closure * (0.38 + 0.62 * sealLevel)));
		}

		/** Hydrostatic + slosh leak rate (mL/sec) from THIS individual seam at given fill & slosh. */
		public double leakRateMlPerSec(double heightFraction, double fillFrac, double sloshPeak, double fluidTempK) {
			if (fillFrac <= 0.005) return 0.0;
			double integ = integrity();
			if (integ >= 0.96) return 0.0;

			// Water head pressure above this seam + inertial wave splash against walls
			double head = Math.max(0.0, fillFrac - heightFraction) + sloshPeak * 0.28 * fillFrac;
			if (head <= 0.01) return 0.0;

			double defectArea = Math.pow(1.0 - integ, 1.65);
			// Hot water softens tallow/wax seals slightly if barely sealed
			double tempSoften = (fluidTempK > 330.0 && sealLevel < 0.75) ? 1.35 : 1.0;
			return defectArea * head * 18.0 * tempSoften;
		}

		public NbtCompound toNbt() {
			NbtCompound t = new NbtCompound();
			t.putDouble("Align", edgeAlignment);
			t.putDouble("Cut", cutQuality);
			t.putInt("Holes", punchedHoles);
			t.putInt("Stitch", stitchedHoles);
			t.putDouble("Tens", meanTension);
			t.putDouble("Tear", tearDamage);
			t.putDouble("Seal", sealLevel);
			t.putDouble("Wear", seamWear);
			return t;
		}

		public static SeamSection fromNbt(NbtCompound t) {
			SeamSection s = new SeamSection();
			if (t.contains("Align")) s.edgeAlignment = t.getDouble("Align");
			if (t.contains("Cut")) s.cutQuality = t.getDouble("Cut");
			s.punchedHoles = t.getInt("Holes");
			s.stitchedHoles = t.getInt("Stitch");
			s.meanTension = t.getDouble("Tens");
			s.tearDamage = t.getDouble("Tear");
			s.sealLevel = t.getDouble("Seal");
			s.seamWear = t.getDouble("Wear");
			return s;
		}
	}

	public final SeamSection[] seams = new SeamSection[SEAM_COUNT];
	public AnimalHideType leatherType = AnimalHideType.COW;
	public double leatherThickness = 0.78;
	public double leatherCleanliness = 0.90;
	public double leatherMoisture = 0.14;
	public double leatherStiffness = 0.28;
	public double discoloration = 0.0;
	public double emptyMassKg = 0.36;
	public int capacityMl = 1000;
	public double consumedCordageMeters = 0.0;

	// Simplified 2D damped-spring fluid-body slosh state inside flexible container
	public double sloshX = 0.0;
	public double sloshZ = 0.0;
	public double sloshVx = 0.0;
	public double sloshVz = 0.0;
	public double expansionFrac = 0.0;
	public double leakResidualMl = 0.0;

	public WaterskinConstruction() {
		for (int i = 0; i < SEAM_COUNT; i++) {
			seams[i] = new SeamSection();
		}
	}

	/** Creates a pristine, well-sewn baseline waterskin (used only for legacy/creative items). */
	public static WaterskinConstruction createCraftedBaseline() {
		WaterskinConstruction c = new WaterskinConstruction();
		for (SeamSection s : c.seams) {
			s.edgeAlignment = 0.92;
			s.cutQuality = 0.90;
			s.punchedHoles = HOLES_PER_SEAM;
			s.stitchedHoles = HOLES_PER_SEAM;
			s.meanTension = 0.76;
			s.sealLevel = 0.95;
		}
		return c;
	}

	/** Initializes a brand-new waterskin clamp assembly from two positioned leather pieces. */
	public static WaterskinConstruction fromPieces(double avgThickness, double avgClean, double avgCutReg,
												   double alignmentAccuracy, AnimalHideType animal,
												   double areaCm2, double stiffness, double moisture) {
		WaterskinConstruction c = new WaterskinConstruction();
		c.leatherType = animal;
		c.leatherThickness = Math.max(0.25, Math.min(1.15, avgThickness));
		c.leatherCleanliness = Math.max(0.0, Math.min(1.0, avgClean));
		c.leatherStiffness = stiffness;
		c.leatherMoisture = moisture;
		// Real physical capacity & empty leather mass emerge directly from cut piece geometry & thickness!
		c.capacityMl = Math.max(400, Math.min(1400, (int) Math.round(areaCm2 * 0.42)));
		c.emptyMassKg = Math.max(0.18, Math.min(0.95, 0.16 + c.leatherThickness * 0.28 + (c.capacityMl / 1000.0) * 0.12));

		for (SeamSection s : c.seams) {
			s.edgeAlignment = Math.max(0.15, Math.min(1.0, alignmentAccuracy));
			s.cutQuality = Math.max(0.20, Math.min(1.0, avgCutReg));
			s.punchedHoles = 0;
			s.stitchedHoles = 0;
			s.meanTension = 0.0;
			s.sealLevel = 0.0;
		}
		return c;
	}

	public boolean isReadyForUse() {
		for (SeamSection s : seams) {
			if (s.stitchedHoles < 3) return false;
		}
		return true;
	}

	/** Steps damped fluid-body slosh & expansion for a flexible waterskin under motion acceleration. */
	public void stepFluidBody(int volumeMl, double accelX, double accelZ, double dtSec) {
		double fill = Math.max(0.0, Math.min(1.0, volumeMl / (double) Math.max(100, capacityMl)));
		// Expansion follows fill fraction while slightly restrained by leather stiffness
		expansionFrac = fill * (1.0 - 0.12 * leatherStiffness);
		if (fill <= 0.01) {
			sloshX = sloshZ = sloshVx = sloshVz = 0.0;
			return;
		}
		// Half-full skins slosh most freely; completely full skins have less free headspace
		double freedom = Math.sin(fill * Math.PI * 0.88);
		double springK = 14.0 + leatherStiffness * 10.0;
		double damping = 3.2 + (1.0 - freedom) * 4.5;

		sloshVx += (-accelX * 0.85 * freedom - springK * sloshX - damping * sloshVx) * dtSec;
		sloshVz += (-accelZ * 0.85 * freedom - springK * sloshZ - damping * sloshVz) * dtSec;
		sloshX = Math.max(-0.45, Math.min(0.45, sloshX + sloshVx * dtSec));
		sloshZ = Math.max(-0.45, Math.min(0.45, sloshZ + sloshVz * dtSec));
	}

	public double sloshMagnitude() {
		return Math.hypot(sloshX, sloshZ);
	}

	/** Returns true physical total mass in kilograms (empty leather + current water contents). */
	public double totalMassKg(int volumeMl) {
		double waterKg = Math.max(0, volumeMl) / 1000.0;
		double wetLeatherBonus = leatherMoisture * emptyMassKg * 0.55;
		return emptyMassKg + wetLeatherBonus + waterKg;
	}

	/** Computes total mL/sec escaping across all 4 seams and fills leakOut if non-null. */
	public double totalLeakMlPerSec(int volumeMl, double fluidTempK, double[] perSeamOut) {
		double fill = Math.max(0.0, Math.min(1.0, volumeMl / (double) Math.max(100, capacityMl)));
		double slosh = sloshMagnitude();
		double total = 0.0;
		for (SeamZone z : SeamZone.values()) {
			double rate = seams[z.index].leakRateMlPerSec(z.heightFraction, fill, slosh, fluidTempK);
			if (perSeamOut != null) {
				perSeamOut[z.index] = rate;
			}
			total += rate;
		}
		return total;
	}

	/** Index (0..3) of the fastest-leaking seam, or -1 if none is leaking. */
	public int worstLeakingSeamIndex(int volumeMl, double fluidTempK) {
		double[] rates = new double[SEAM_COUNT];
		totalLeakMlPerSec(volumeMl, fluidTempK, rates);
		int best = -1;
		double maxR = 0.02;
		for (int i = 0; i < SEAM_COUNT; i++) {
			if (rates[i] > maxR) {
				maxR = rates[i];
				best = i;
			}
		}
		return best;
	}

	public double overallIntegrity() {
		double min = 1.0;
		double sum = 0.0;
		for (SeamSection s : seams) {
			double v = s.integrity();
			min = Math.min(min, v);
			sum += v;
		}
		return 0.5 * min + 0.5 * (sum / SEAM_COUNT);
	}

	/** Reads construction data from stack NBT (or initializes legacy crafted baseline if absent). */
	public static WaterskinConstruction read(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp == null) {
			return createCraftedBaseline();
		}
		NbtCompound nbt = comp.copyNbt();
		if (!nbt.contains("WSConstr")) {
			return createCraftedBaseline();
		}
		return fromNbt(nbt.getCompound("WSConstr"));
	}

	public static void write(ItemStack stack, WaterskinConstruction c) {
		if (stack.isEmpty() || c == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.put("WSConstr", c.toNbt()));
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putString("Leather", leatherType.id);
		t.putDouble("Thick", leatherThickness);
		t.putDouble("Clean", leatherCleanliness);
		t.putDouble("Moist", leatherMoisture);
		t.putDouble("Stiff", leatherStiffness);
		t.putDouble("Discol", discoloration);
		t.putDouble("EmptyKg", emptyMassKg);
		t.putInt("CapMl", capacityMl);
		t.putDouble("CordM", consumedCordageMeters);
		t.putDouble("SlX", sloshX);
		t.putDouble("SlZ", sloshZ);
		t.putDouble("SlVx", sloshVx);
		t.putDouble("SlVz", sloshVz);
		t.putDouble("Exp", expansionFrac);
		t.putDouble("LeakRes", leakResidualMl);
		for (int i = 0; i < SEAM_COUNT; i++) {
			t.put("S" + i, seams[i].toNbt());
		}
		return t;
	}

	public static WaterskinConstruction fromNbt(NbtCompound t) {
		WaterskinConstruction c = new WaterskinConstruction();
		if (t.contains("Leather")) c.leatherType = AnimalHideType.fromId(t.getString("Leather"));
		if (t.contains("Thick")) c.leatherThickness = t.getDouble("Thick");
		if (t.contains("Clean")) c.leatherCleanliness = t.getDouble("Clean");
		if (t.contains("Moist")) c.leatherMoisture = t.getDouble("Moist");
		if (t.contains("Stiff")) c.leatherStiffness = t.getDouble("Stiff");
		c.discoloration = t.getDouble("Discol");
		if (t.contains("EmptyKg")) c.emptyMassKg = t.getDouble("EmptyKg");
		if (t.contains("CapMl")) c.capacityMl = Math.max(250, t.getInt("CapMl"));
		c.consumedCordageMeters = t.getDouble("CordM");
		c.sloshX = t.getDouble("SlX");
		c.sloshZ = t.getDouble("SlZ");
		c.sloshVx = t.getDouble("SlVx");
		c.sloshVz = t.getDouble("SlVz");
		c.expansionFrac = t.getDouble("Exp");
		c.leakResidualMl = t.getDouble("LeakRes");
		for (int i = 0; i < SEAM_COUNT; i++) {
			if (t.contains("S" + i)) {
				c.seams[i] = SeamSection.fromNbt(t.getCompound("S" + i));
			}
		}
		return c;
	}
}
