package com.projectrealism.anatomy;

import com.projectrealism.material.AnimalHideType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.GoatEntity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.WolfEntity;

/**
 * Species-specific anatomical archetypes ensuring fundamentally distinct skeletal proportions,
 * tissue distributions, wool/horn availability, organ masses, blood volume, and cooling geometry.
 * A rabbit is NOT a scaled-down cow: its bone sizes, fat fraction, wool, horns, and cooling
 * surface-area ratio produce completely different materials and spoilage behavior.
 */
public enum AnimalSpecies {
	COW("cow", AnimalHideType.COW, 220.0, 2.6, 14.0, 0.0, 2, 1.4, 2.2,
			16.0, 24.0, 28.0, 1.6, 4.2, 7.5,
			BoneData.BoneSize.LARGE, 8, 34.0, 32.0, 0.92,
			0.075, 0.65, 1.15f),
	HORSE("horse", AnimalHideType.HORSE, 240.0, 2.8, 8.0, 0.0, 0, 1.8, 3.0,
			18.0, 16.0, 38.0, 1.8, 4.0, 7.0,
			BoneData.BoneSize.LARGE, 8, 38.0, 30.0, 0.96,
			0.078, 0.55, 1.20f),
	PIG("pig", AnimalHideType.PIG, 85.0, 1.4, 18.0, 0.0, 0, 0.7, 1.1,
			7.5, 13.0, 11.0, 0.6, 2.1, 4.2,
			BoneData.BoneSize.MEDIUM, 6, 18.0, 19.0, 0.80,
			0.068, 0.85, 0.80f),
	SHEEP("sheep", AnimalHideType.SHEEP, 52.0, 1.1, 6.5, 2.8, 2, 0.55, 0.85,
			5.2, 6.0, 8.5, 0.45, 1.3, 2.8,
			BoneData.BoneSize.MEDIUM, 6, 17.0, 16.0, 0.78,
			0.066, 1.20, 0.75f),
	GOAT("goat", AnimalHideType.GOAT, 48.0, 1.0, 3.8, 0.0, 2, 0.60, 0.95,
			4.8, 3.8, 9.2, 0.42, 1.1, 2.5,
			BoneData.BoneSize.MEDIUM, 6, 17.0, 15.0, 0.84,
			0.065, 0.60, 0.75f),
	WOLF("wolf", AnimalHideType.GOAT, 34.0, 0.9, 2.5, 0.0, 0, 0.50, 0.85,
			3.2, 2.4, 6.8, 0.35, 0.9, 1.9,
			BoneData.BoneSize.MEDIUM, 6, 15.0, 13.0, 0.82,
			0.072, 0.75, 0.68f),
	RABBIT("rabbit", AnimalHideType.RABBIT, 1.9, 0.25, 0.08, 0.0, 0, 0.05, 0.08,
			0.28, 0.06, 0.34, 0.03, 0.08, 0.14,
			BoneData.BoneSize.SMALL, 4, 7.2, 5.2, 0.56,
			0.062, 0.40, 0.38f),
	CHICKEN("chicken", AnimalHideType.RABBIT, 2.1, 0.10, 0.14, 0.0, 0, 0.04, 0.09,
			0.35, 0.10, 0.32, 0.03, 0.09, 0.12,
			BoneData.BoneSize.TINY, 4, 6.8, 4.8, 0.48,
			0.060, 0.35, 0.36f);

	public final String id;
	public final AnimalHideType hideType;
	public final double liveMassKg;
	public final double hideMassKg;
	public final double baseFatKg;
	public final double baseWoolKg;
	public final int hornCount;
	public final double baseSinewKg;
	public final double baseTendonKg;
	public final double primeMeatKg;
	public final double fattyMeatKg;
	public final double toughMeatKg;
	public final double heartKg;
	public final double liverKg;
	public final double gutKg;
	public final BoneData.BoneSize primaryBoneSize;
	public final int usableBoneCount;
	public final double boneLengthCm;
	public final double boneDiameterMm;
	public final double boneDensity;
	public final double bloodMassFraction;
	public final double coatInsulation; // thermal resistance of coat/wool/fat
	public final float visualScale;

	AnimalSpecies(String id, AnimalHideType hideType, double liveMassKg, double hideMassKg,
				  double baseFatKg, double baseWoolKg, int hornCount, double baseSinewKg, double baseTendonKg,
				  double primeMeatKg, double fattyMeatKg, double toughMeatKg,
				  double heartKg, double liverKg, double gutKg,
				  BoneData.BoneSize primaryBoneSize, int usableBoneCount,
				  double boneLengthCm, double boneDiameterMm, double boneDensity,
				  double bloodMassFraction, double coatInsulation, float visualScale) {
		this.id = id;
		this.hideType = hideType;
		this.liveMassKg = liveMassKg;
		this.hideMassKg = hideMassKg;
		this.baseFatKg = baseFatKg;
		this.baseWoolKg = baseWoolKg;
		this.hornCount = hornCount;
		this.baseSinewKg = baseSinewKg;
		this.baseTendonKg = baseTendonKg;
		this.primeMeatKg = primeMeatKg;
		this.fattyMeatKg = fattyMeatKg;
		this.toughMeatKg = toughMeatKg;
		this.heartKg = heartKg;
		this.liverKg = liverKg;
		this.gutKg = gutKg;
		this.primaryBoneSize = primaryBoneSize;
		this.usableBoneCount = usableBoneCount;
		this.boneLengthCm = boneLengthCm;
		this.boneDiameterMm = boneDiameterMm;
		this.boneDensity = boneDensity;
		this.bloodMassFraction = bloodMassFraction;
		this.coatInsulation = coatInsulation;
		this.visualScale = visualScale;
	}

	public boolean hasWoolCoat() {
		return baseWoolKg > 0.05;
	}

	public boolean hasUsableHide() {
		return this != CHICKEN;
	}

	public static AnimalSpecies fromId(String id) {
		for (AnimalSpecies s : values()) {
			if (s.id.equalsIgnoreCase(id)) return s;
		}
		return COW;
	}

	public static AnimalSpecies fromEntity(LivingEntity entity) {
		if (entity instanceof CowEntity) return COW;
		if (entity instanceof HorseEntity) return HORSE;
		if (entity instanceof PigEntity) return PIG;
		if (entity instanceof SheepEntity) return SHEEP;
		if (entity instanceof GoatEntity) return GOAT;
		if (entity instanceof WolfEntity) return WOLF;
		if (entity instanceof RabbitEntity) return RABBIT;
		if (entity instanceof ChickenEntity) return CHICKEN;
		return SHEEP;
	}

	public static boolean isSimulatedAnimal(EntityType<?> type) {
		return type == EntityType.COW || type == EntityType.MOOSHROOM
				|| type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE
				|| type == EntityType.PIG || type == EntityType.SHEEP
				|| type == EntityType.GOAT || type == EntityType.WOLF
				|| type == EntityType.RABBIT || type == EntityType.CHICKEN;
	}
}
