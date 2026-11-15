package com.projectrealism.anatomy;

import com.projectrealism.material.HideSurface;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Full persistent anatomical carcass model tracking multi-region tissues, blood circulation/clotting,
 * thermodynamic core cooling, rigor mortis, Q10 + insect + moisture decomposition, microbiology hooks,
 * and gradual visible material removal across every anatomical section.
 */
public final class CarcassState {
	public enum Region {
		HEAD_NECK(0, "head_neck"),
		COAT_TORSO(1, "coat_torso"),
		LOIN_RIBS(2, "loin_ribs"),
		HAUNCH_LIMB(3, "haunch_limb"),
		VISCERA_CAVITY(4, "viscera_cavity");

		public final int index;
		public final String key;

		Region(int index, String key) {
			this.index = index;
			this.key = key;
		}
	}

	/**
	 * Maps a block-hit position onto normalized carcass bed coordinates aligned with the renderer:
	 * {@code u} (Vec3d.x) spans across the flanks (0 = left, 1 = right), and {@code v} (Vec3d.z) runs
	 * front-to-back along the body axis (0 = head/neck end, 1 = hindquarters/limbs).
	 */
	public static Vec3d localBedUV(Direction facing, BlockPos pos, Vec3d hitPos) {
		double lx = Math.max(0.02, Math.min(0.98, hitPos.x - pos.getX()));
		double lz = Math.max(0.02, Math.min(0.98, hitPos.z - pos.getZ()));
		double u;
		double v;
		switch (facing) {
			case SOUTH -> { u = 1.0 - lx; v = 1.0 - lz; }
			case EAST  -> { u = lz;       v = 1.0 - lx; }
			case WEST  -> { u = 1.0 - lz; v = lx;       }
			default    -> { u = lx;       v = lz;       } // NORTH: head renders at -Z (low lz)
		}
		return new Vec3d(u, hitPos.y - pos.getY(), v);
	}

	public static Region regionFromUV(double u, double v) {
		if (v < 0.28) return Region.HEAD_NECK;
		if (v > 0.72) return Region.HAUNCH_LIMB;
		if (u < 0.36) return Region.VISCERA_CAVITY;
		if (u > 0.68) return Region.LOIN_RIBS;
		return Region.COAT_TORSO;
	}

	public AnimalSpecies species = AnimalSpecies.COW;
	public double ageMaturity = 0.85;
	public double bodyCondition = 0.75;
	public double fatigueAtDeath = 0.10;
	public double diseaseLoad = 0.0;

	// Live-to-carcass thermodynamic & biological state
	public double bodyTempK = 311.5;
	public double remainingBloodFrac = 1.0;  // 1.0 = unbled full blood, 0.08 = cleanly bled out
	public double tissueMoisture = 0.74;
	public double structuralCondition = 1.0; // decreases with rot, heavy blunt trauma, gut tear
	public double ageSeconds = 0.0;
	public double rigorMortisFrac = 0.0;     // rises post-mortem then relaxes
	public double spoilageFrac = 0.0;        // 0 = pristine fresh, 1 = rotten/putrid
	public double insectActivity = 0.0;      // fly/larvae intensity in warm humid exposed air
	public double microbialLoad = 0.04;      // microbiology decomposition & pathogen hook
	public double gutContamination = 0.0;    // bile/rumen spill if viscera punctured carelessly

	// Processing flags & remaining anatomical material pools
	public boolean bleedIncised = false;
	public boolean eviscerated = false;
	public double woolRemainingKg = 0.0;
	public double skinProgress = 0.0;        // 0..1 progressive seam freeing -> drops hide at 1.0
	public boolean hidePresent = true;
	public double hideTrauma = 0.15;
	public boolean fireKilled = false;

	public double fatRemainingKg = 0.0;
	public double sinewRemainingKg = 0.0;
	public double tendonRemainingKg = 0.0;
	public double primeMeatRemainingKg = 0.0;
	public double fattyMeatRemainingKg = 0.0;
	public double toughMeatRemainingKg = 0.0;

	public boolean heartPresent = true;
	public boolean liverPresent = true;
	public boolean gutPresent = true;
	public int hornsRemaining = 0;
	public final List<BoneData> bonesRemaining = new ArrayList<>();

	public CarcassState() {
	}

	/** Constructs a persistent carcass directly from a living animal's physiological state. */
	public static CarcassState fromLivingAnimal(AnimalPhysiologyState phys, boolean fireDamage, double killTrauma) {
		CarcassState c = new CarcassState();
		AnimalSpecies sp = phys.species;
		c.species = sp;
		c.ageMaturity = phys.ageMaturity;
		c.bodyCondition = phys.bodyCondition;
		c.fatigueAtDeath = phys.fatigueFrac;
		c.diseaseLoad = phys.diseaseLoad;
		c.bodyTempK = phys.bodyTempK;
		c.microbialLoad = Math.max(0.03, Math.min(1.0, 0.03 + phys.diseaseLoad * 0.7));
		c.tissueMoisture = Math.max(0.45, Math.min(0.85, 0.56 + phys.hydrationFrac * 0.24));
		c.fireKilled = fireDamage;
		c.hideTrauma = Math.min(0.85, killTrauma + phys.injuryTorso * 0.35 + phys.injuryLimb * 0.20);
		c.structuralCondition = Math.max(0.25, 1.0 - c.hideTrauma * 0.35);

		double sizeScale = 0.45 + 0.55 * phys.ageMaturity;
		double condScale = 0.50 + 0.65 * phys.bodyCondition;

		c.woolRemainingKg = (sp.hasWoolCoat() && !phys.woolShorn) ? sp.baseWoolKg * sizeScale : 0.0;
		c.hidePresent = sp.hasUsableHide();
		c.fatRemainingKg = sp.baseFatKg * sizeScale * (0.20 + 1.15 * phys.fatReserveFrac);
		c.sinewRemainingKg = sp.baseSinewKg * sizeScale;
		c.tendonRemainingKg = sp.baseTendonKg * sizeScale;
		c.primeMeatRemainingKg = sp.primeMeatKg * sizeScale * condScale;
		c.fattyMeatRemainingKg = sp.fattyMeatKg * sizeScale * (0.45 + 0.70 * phys.fatReserveFrac);
		c.toughMeatRemainingKg = sp.toughMeatKg * sizeScale * condScale;
		c.hornsRemaining = phys.ageMaturity >= 0.55 ? sp.hornCount : 0;

		for (int i = 0; i < sp.usableBoneCount; i++) {
			BoneData.BoneType bt = (i % 3 == 1) ? BoneData.BoneType.RIB
					: (i % 3 == 2 ? BoneData.BoneType.SCAPULA : BoneData.BoneType.LONG_SHAFT);
			c.bonesRemaining.add(new BoneData(sp, bt, phys.ageMaturity));
		}
		return c;
	}

	/** Current real total mass in kilograms of everything remaining on the carcass. */
	public double currentTotalMassKg() {
		double scale = 0.45 + 0.55 * ageMaturity;
		double bloodKg = species.liveMassKg * species.bloodMassFraction * scale * remainingBloodFrac;
		double hideKg = hidePresent ? species.hideMassKg * scale * (1.0 - skinProgress * 0.4) : 0.0;
		double organsKg = (heartPresent ? species.heartKg : 0.0)
				+ (liverPresent ? species.liverKg : 0.0)
				+ (gutPresent ? species.gutKg : 0.0);
		double hornKg = hornsRemaining * (species == AnimalSpecies.COW ? 0.85 : 0.45) * scale;
		double bonesKg = 0.0;
		for (BoneData b : bonesRemaining) {
			bonesKg += b.massKg();
		}
		double waterMod = 0.72 + 0.38 * tissueMoisture;
		double tissues = woolRemainingKg + hideKg + fatRemainingKg + sinewRemainingKg
				+ tendonRemainingKg + (primeMeatRemainingKg + fattyMeatRemainingKg + toughMeatRemainingKg + organsKg) * waterMod;
		return Math.max(0.05, tissues + bloodKg + hornKg + bonesKg);
	}

	public boolean isSkeletonOnly() {
		return !hidePresent && woolRemainingKg <= 0.02 && fatRemainingKg <= 0.04
				&& primeMeatRemainingKg <= 0.05 && fattyMeatRemainingKg <= 0.05 && toughMeatRemainingKg <= 0.05
				&& !heartPresent && !liverPresent && !gutPresent && sinewRemainingKg <= 0.02 && tendonRemainingKg <= 0.02;
	}

	public boolean isCompletelyExhausted() {
		return isSkeletonOnly() && hornsRemaining <= 0 && bonesRemaining.isEmpty();
	}

	/**
	 * Steps thermal cooling/freezing, blood drainage & clotting, rigor mortis, insect activity,
	 * moisture evaporation/rain absorption, and Q10 decomposition coupled to {@code thermo}.
	 */
	public void stepEnvironment(double airTempK, double relHumidity, double airSpeedMS,
								double radiantHeatW, boolean exposedRain, boolean sheltered, double dtSec) {
		ageSeconds += dtSec;

		// 1. Core thermal transfer: coat, fat, and closed viscera insulate core heat heavily!
		double effTempK = airTempK + radiantHeatW / 55.0;
		double insulation = (woolRemainingKg > 0.2 ? species.coatInsulation * 0.65 : 0.0)
				+ (hidePresent ? species.coatInsulation * 0.40 : 0.0)
				+ Math.min(0.65, fatRemainingKg * 0.04)
				+ (eviscerated ? 0.0 : 0.55);
		double coolingSizeFactor = Math.pow(3.0 / Math.max(0.5, currentTotalMassKg()), 0.42);
		double hConv = 0.0038 * (0.45 + airSpeedMS * 0.60) * coolingSizeFactor / (1.0 + insulation);
		bodyTempK += (effTempK - bodyTempK) * Math.min(0.35, hConv * dtSec);

		// 2. Bleed-out drainage while blood remains warm & fluid (clots once chilled < 296 K)
		if (bleedIncised && remainingBloodFrac > 0.08) {
			double fluidity = bodyTempK > 296.0 ? 1.0 : Math.max(0.08, (bodyTempK - 280.0) / 16.0);
			remainingBloodFrac = Math.max(0.08, remainingBloodFrac - 0.018 * fluidity * dtSec);
		}

		// 3. Tissue moisture drying vs rain wetting
		if (exposedRain) {
			tissueMoisture = Math.min(0.92, tissueMoisture + 0.0008 * dtSec);
		} else {
			double eqM = Math.max(0.18, 0.20 + relHumidity * 0.35);
			double dryAccess = (hidePresent ? 0.25 : 1.0) * (0.4 + airSpeedMS * 0.45);
			tissueMoisture = Math.max(0.15, tissueMoisture - (tissueMoisture - eqM) * 0.00018 * dryAccess * dtSec);
		}

		// 4. Rigor mortis wave: builds over first ~3 hours (faster warm/fatigued), then relaxes
		double peakSec = Math.max(1800.0, 7200.0 * (1.0 - fatigueAtDeath * 0.45) * (bodyTempK > 298.0 ? 0.7 : 1.2));
		if (ageSeconds < peakSec) {
			rigorMortisFrac = Math.min(1.0, ageSeconds / peakSec);
		} else {
			rigorMortisFrac = Math.max(0.10, 1.0 - (ageSeconds - peakSec) / (peakSec * 2.5));
		}

		// 5. Frozen carcasses halt enzymatic/microbial spoilage & insects completely
		if (bodyTempK <= 273.15) {
			insectActivity = Math.max(0.0, insectActivity - 0.01 * dtSec);
			return;
		}

		// 6. Insect activity peaks in warm humid still open air; exposed muscle attracts flies faster than intact hide
		if (airTempK > 286.0 && airTempK < 318.0 && !sheltered && airSpeedMS < 2.2) {
			double exposeBoost = !hidePresent ? 1.35 : 0.90;
			double targetInsects = Math.min(1.0, ((airTempK - 285.0) / 22.0) * (0.4 + 0.6 * relHumidity)
					* exposeBoost * (1.0 - Math.min(0.75, airSpeedMS * 0.35)));
			insectActivity += (targetInsects - insectActivity) * 0.0018 * dtSec;
		} else {
			insectActivity = Math.max(0.0, insectActivity - 0.003 * dtSec);
		}

		// 7. Continuous Q10 decomposition driven by temp, viscera heat, pooled blood, moisture, insects & microbes
		double q10 = Math.pow(2.0, (Math.min(318.0, bodyTempK) - 277.0) / 10.0);
		double bloodRotBoost = 1.0 + remainingBloodFrac * 0.85;
		double visceraRotBoost = (!eviscerated && gutPresent) ? 1.75 : 1.0;
		double moistureGate = Math.max(0.18, tissueMoisture);
		double flyFactor = 1.0 + insectActivity * 1.45;
		double contamFactor = 1.0 + gutContamination * 2.2 + microbialLoad * 1.25;

		double rotRate = 0.0000045 * q10 * bloodRotBoost * visceraRotBoost * moistureGate * flyFactor * contamFactor;
		spoilageFrac = Math.min(1.0, spoilageFrac + rotRate * dtSec);
		structuralCondition = Math.max(0.05, structuralCondition - rotRate * 0.55 * dtSec);
		if (bodyTempK >= 285.0 && bodyTempK <= 325.0) {
			microbialLoad = Math.min(1.0, microbialLoad + rotRate * 1.35 * dtSec);
		}
	}

	// -------------------------------------------------- Interactive Actions ---

	public double shearWoolKg() {
		if (woolRemainingKg <= 0.02) return 0.0;
		double take = Math.min(woolRemainingKg, Math.max(0.45, woolRemainingKg * 0.5));
		woolRemainingKg = Math.max(0.0, woolRemainingKg - take);
		return take;
	}

	public boolean stickNeckToBleed() {
		if (bleedIncised && remainingBloodFrac <= 0.12) return false;
		bleedIncised = true;
		// Immediate arterial gush drains trapped neck blood quickly if still warm
		double immediateDrain = bodyTempK > 294.0 ? 0.32 : 0.10;
		remainingBloodFrac = Math.max(0.08, remainingBloodFrac - immediateDrain);
		return true;
	}

	/**
	 * Works skinning cuts around the carcass; at 100% separation detaches a physical {@link HideSurface}
	 * matching the animal's exact species, age, coat/fat condition, and combat wounds!
	 */
	public HideSurface skinStep(double knifeSharpness, Random rng) {
		if (!hidePresent) return null;
		double step = (0.28 + 0.30 * knifeSharpness) * (species == AnimalSpecies.RABBIT ? 1.8 : 1.0);
		skinProgress = Math.min(1.0, skinProgress + step);
		// Dull knife slightly scores the inside grain of the hide
		if (knifeSharpness < 0.45) {
			hideTrauma = Math.min(0.95, hideTrauma + 0.06);
		}
		if (skinProgress < 1.0) {
			return null;
		}
		hidePresent = false;
		HideSurface hs = new HideSurface(species.hideType);
		hs.applyKillTrauma(rng, hideTrauma, fireKilled);
		for (int i = 0; i < HideSurface.CELLS; i++) {
			hs.moisture[i] = Math.max(0.25, Math.min(0.90, tissueMoisture));
			hs.thickness[i] *= (0.65 + 0.35 * ageMaturity);
		}
		return hs;
	}

	public double flenseFatKg(double bladeSharpness) {
		if (fatRemainingKg <= 0.05) return 0.0;
		double chunk = Math.min(fatRemainingKg, Math.max(0.35, fatRemainingKg * (0.25 + 0.25 * bladeSharpness)));
		fatRemainingKg = Math.max(0.0, fatRemainingKg - chunk);
		return chunk;
	}

	public double extractSinewKg() {
		if (sinewRemainingKg <= 0.02) return 0.0;
		double chunk = Math.min(sinewRemainingKg, Math.max(0.10, sinewRemainingKg * 0.45));
		sinewRemainingKg = Math.max(0.0, sinewRemainingKg - chunk);
		return chunk;
	}

	public double extractTendonKg() {
		if (tendonRemainingKg <= 0.02) return 0.0;
		double chunk = Math.min(tendonRemainingKg, Math.max(0.10, tendonRemainingKg * 0.45));
		tendonRemainingKg = Math.max(0.0, tendonRemainingKg - chunk);
		return chunk;
	}

	public static final class EviscerateOutcome {
		public final boolean newlyOpened;
		public final boolean rupturedGut;
		public final MeatCutData harvestedOrgan;

		public EviscerateOutcome(boolean newlyOpened, boolean rupturedGut, MeatCutData harvestedOrgan) {
			this.newlyOpened = newlyOpened;
			this.rupturedGut = rupturedGut;
			this.harvestedOrgan = harvestedOrgan;
		}
	}

	/** Opens visceral cavity cleanly (or punctures gut if blade is dull!) and harvests heart, liver, or gut. */
	public EviscerateOutcome processViscera(double knifeSharpness, Random rng) {
		if (!eviscerated) {
			eviscerated = true;
			boolean ruptured = knifeSharpness < 0.35 && rng.nextDouble() < 0.45;
			if (ruptured) {
				gutContamination = Math.min(1.0, gutContamination + 0.45);
			}
			return new EviscerateOutcome(true, ruptured, null);
		}
		MeatCutData.CutKind chosen = null;
		double mass = 0.0;
		if (heartPresent) {
			heartPresent = false;
			chosen = MeatCutData.CutKind.HEART;
			mass = species.heartKg * (0.5 + 0.5 * ageMaturity);
		} else if (liverPresent) {
			liverPresent = false;
			chosen = MeatCutData.CutKind.LIVER;
			mass = species.liverKg * (0.5 + 0.5 * ageMaturity);
		} else if (gutPresent) {
			gutPresent = false;
			chosen = MeatCutData.CutKind.GUT_CASING;
			mass = species.gutKg * (0.5 + 0.5 * ageMaturity);
		}
		if (chosen == null) return new EviscerateOutcome(false, false, null);
		MeatCutData data = new MeatCutData(species, chosen, mass, bodyCondition, fatigueAtDeath,
				bodyTempK, spoilageFrac, gutContamination, microbialLoad);
		return new EviscerateOutcome(false, false, data);
	}

	public MeatCutData butcherMuscle(MeatCutData.CutKind kind, double bladeSharpness) {
		double avail = switch (kind) {
			case PRIME_LOIN -> primeMeatRemainingKg;
			case FATTY_RIB -> fattyMeatRemainingKg;
			default -> toughMeatRemainingKg;
		};
		if (avail <= 0.04) return null;
		double maxPortion = Math.min(avail, Math.max(0.28, species.liveMassKg * 0.045));
		double yieldFrac = 0.72 + 0.28 * bladeSharpness;
		double take = Math.min(avail, maxPortion);
		switch (kind) {
			case PRIME_LOIN -> primeMeatRemainingKg = Math.max(0.0, primeMeatRemainingKg - take);
			case FATTY_RIB -> fattyMeatRemainingKg = Math.max(0.0, fattyMeatRemainingKg - take);
			default -> toughMeatRemainingKg = Math.max(0.0, toughMeatRemainingKg - take);
		}
		double cutMass = take * yieldFrac;
		double bloodSpoil = remainingBloodFrac > 0.6 ? 0.08 : 0.0;
		return new MeatCutData(species, kind, cutMass, bodyCondition, fatigueAtDeath,
				bodyTempK, Math.min(1.0, spoilageFrac + bloodSpoil), gutContamination, microbialLoad);
	}

	public boolean removeOneHorn() {
		if (hornsRemaining <= 0) return false;
		hornsRemaining--;
		return true;
	}

	public BoneData disarticulateOneBone() {
		if (bonesRemaining.isEmpty()) return null;
		return bonesRemaining.remove(0);
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putString("Species", species.id);
		t.putDouble("Age", ageMaturity);
		t.putDouble("Cond", bodyCondition);
		t.putDouble("Fatig", fatigueAtDeath);
		t.putDouble("Dis", diseaseLoad);
		t.putDouble("TempK", bodyTempK);
		t.putDouble("Blood", remainingBloodFrac);
		t.putDouble("Moist", tissueMoisture);
		t.putDouble("Struct", structuralCondition);
		t.putDouble("AgeS", ageSeconds);
		t.putDouble("Rigor", rigorMortisFrac);
		t.putDouble("Spoil", spoilageFrac);
		t.putDouble("Insects", insectActivity);
		t.putDouble("Microb", microbialLoad);
		t.putDouble("Contam", gutContamination);
		t.putBoolean("Bled", bleedIncised);
		t.putBoolean("Evis", eviscerated);
		t.putDouble("Wool", woolRemainingKg);
		t.putDouble("SkinP", skinProgress);
		t.putBoolean("HideP", hidePresent);
		t.putDouble("HideT", hideTrauma);
		t.putBoolean("FireK", fireKilled);
		t.putDouble("FatKg", fatRemainingKg);
		t.putDouble("SinewKg", sinewRemainingKg);
		t.putDouble("TendonKg", tendonRemainingKg);
		t.putDouble("PrimeKg", primeMeatRemainingKg);
		t.putDouble("FattyKg", fattyMeatRemainingKg);
		t.putDouble("ToughKg", toughMeatRemainingKg);
		t.putBoolean("Heart", heartPresent);
		t.putBoolean("Liver", liverPresent);
		t.putBoolean("Gut", gutPresent);
		t.putInt("Horns", hornsRemaining);
		NbtList bl = new NbtList();
		for (BoneData b : bonesRemaining) {
			bl.add(b.toNbt());
		}
		t.put("Bones", bl);
		return t;
	}

	public static CarcassState fromNbt(NbtCompound t) {
		CarcassState c = new CarcassState();
		c.species = AnimalSpecies.fromId(t.getString("Species"));
		if (t.contains("Age")) c.ageMaturity = t.getDouble("Age");
		if (t.contains("Cond")) c.bodyCondition = t.getDouble("Cond");
		c.fatigueAtDeath = t.getDouble("Fatig");
		c.diseaseLoad = t.getDouble("Dis");
		if (t.contains("TempK")) c.bodyTempK = t.getDouble("TempK");
		if (t.contains("Blood")) c.remainingBloodFrac = t.getDouble("Blood");
		if (t.contains("Moist")) c.tissueMoisture = t.getDouble("Moist");
		if (t.contains("Struct")) c.structuralCondition = t.getDouble("Struct");
		c.ageSeconds = t.getDouble("AgeS");
		c.rigorMortisFrac = t.getDouble("Rigor");
		c.spoilageFrac = t.getDouble("Spoil");
		c.insectActivity = t.getDouble("Insects");
		c.microbialLoad = t.getDouble("Microb");
		c.gutContamination = t.getDouble("Contam");
		c.bleedIncised = t.getBoolean("Bled");
		c.eviscerated = t.getBoolean("Evis");
		c.woolRemainingKg = t.getDouble("Wool");
		c.skinProgress = t.getDouble("SkinP");
		c.hidePresent = !t.contains("HideP") || t.getBoolean("HideP");
		c.hideTrauma = t.getDouble("HideT");
		c.fireKilled = t.getBoolean("FireK");
		c.fatRemainingKg = t.getDouble("FatKg");
		c.sinewRemainingKg = t.getDouble("SinewKg");
		c.tendonRemainingKg = t.getDouble("TendonKg");
		c.primeMeatRemainingKg = t.getDouble("PrimeKg");
		c.fattyMeatRemainingKg = t.getDouble("FattyKg");
		c.toughMeatRemainingKg = t.getDouble("ToughKg");
		c.heartPresent = !t.contains("Heart") || t.getBoolean("Heart");
		c.liverPresent = !t.contains("Liver") || t.getBoolean("Liver");
		c.gutPresent = !t.contains("Gut") || t.getBoolean("Gut");
		c.hornsRemaining = t.getInt("Horns");
		NbtList bl = t.getList("Bones", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < bl.size(); i++) {
			c.bonesRemaining.add(BoneData.fromNbt(bl.getCompound(i)));
		}
		return c;
	}
}
