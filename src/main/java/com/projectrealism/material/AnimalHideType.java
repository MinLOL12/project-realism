package com.projectrealism.material;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.GoatEntity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.LivingEntity;

/**
 * Anatomically informed hide archetypes defining real surface size, dry/wet base mass,
 * fat yield, tensile strength, and regional thickness variation (spine ridge vs flanks vs belly).
 */
public enum AnimalHideType {
	COW("cow", 140.0, 110.0, 2.6, 0.90, 1.00, 0.45),
	HORSE("horse", 150.0, 115.0, 2.8, 0.70, 1.05, 0.40),
	PIG("pig", 100.0, 80.0, 1.4, 1.25, 0.72, 0.65),
	SHEEP("sheep", 95.0, 75.0, 1.1, 0.60, 0.62, 0.55),
	GOAT("goat", 90.0, 72.0, 1.0, 0.55, 0.78, 0.50),
	RABBIT("rabbit", 42.0, 32.0, 0.25, 0.12, 0.35, 0.35);

	public final String id;
	public final double widthCm;
	public final double heightCm;
	public final double dryMassKg;
	public final double initialFatKg;
	public final double baseThickness;
	public final double initialWaterResistance;

	AnimalHideType(String id, double widthCm, double heightCm, double dryMassKg,
				   double initialFatKg, double baseThickness, double initialWaterResistance) {
		this.id = id;
		this.widthCm = widthCm;
		this.heightCm = heightCm;
		this.dryMassKg = dryMassKg;
		this.initialFatKg = initialFatKg;
		this.baseThickness = baseThickness;
		this.initialWaterResistance = initialWaterResistance;
	}

	/**
	 * Anatomical thickness map across an 8x8 dorsal-to-belly hide grid:
	 * central vertical columns (3..4) represent the thick dorsal spine ridge,
	 * middle columns represent flanks, and outer corners represent thin legs/belly edges.
	 */
	public double regionalThicknessAt(int gx, int gy) {
		double dx = Math.abs(gx - 3.5) / 3.5; // 0 at spine, 1 at belly edge
		double dy = Math.abs(gy - 3.5) / 3.5; // 0 at torso mid, 1 at neck/rump
		double dorsalFactor = 1.0 - 0.55 * dx - 0.22 * dy;
		return Math.max(0.20, Math.min(1.15, baseThickness * dorsalFactor));
	}

	/** Usable biological silhouette (outer neck/limb cutouts so raw hides are irregularly shaped). */
	public boolean defaultIntactAt(int gx, int gy) {
		if ((gx == 0 || gx == 7) && (gy == 0 || gy == 7)) {
			return false; // limb corner indentations
		}
		if ((gx == 1 || gx == 6) && gy == 0) {
			return false; // neck taper
		}
		return true;
	}

	public static AnimalHideType fromEntity(LivingEntity entity) {
		if (entity instanceof CowEntity) return COW;
		if (entity instanceof HorseEntity) return HORSE;
		if (entity instanceof PigEntity) return PIG;
		if (entity instanceof SheepEntity) return SHEEP;
		if (entity instanceof GoatEntity) return GOAT;
		if (entity instanceof RabbitEntity) return RABBIT;
		return SHEEP;
	}

	public static AnimalHideType fromId(String id) {
		for (AnimalHideType t : values()) {
			if (t.id.equalsIgnoreCase(id)) return t;
		}
		return COW;
	}

	public static boolean isHideBearing(EntityType<?> type) {
		return type == EntityType.COW || type == EntityType.MOOSHROOM
				|| type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE
				|| type == EntityType.PIG || type == EntityType.SHEEP
				|| type == EntityType.GOAT || type == EntityType.RABBIT;
	}
}
