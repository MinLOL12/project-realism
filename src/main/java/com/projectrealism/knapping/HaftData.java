package com.projectrealism.knapping;

import net.minecraft.nbt.NbtCompound;

/**
 * Physical wooden-handle mounting state attached directly to any shaped rock without converting it
 * into a rigid recipe category. Evaluates handle orientation, socket contact area, fit quality,
 * working-edge clearance, binding tension, slip resistance, and combined moment arm.
 */
public final class HaftData {
	public enum Orientation {
		/** Working axis extends straight ahead of handle (knife, dagger, spear, burin). */
		INLINE("inline", 1.0, 0.25),
		/** Working edge sits perpendicular side-on to handle swing (chopper, axe-like swing). */
		PERPENDICULAR_CHOP("perpendicular", 1.85, 1.0),
		/** Broad bevel angled transversely across handle tip (adze, planing wedge, hoe). */
		TRANSVERSE_ADZE("transverse", 1.55, 0.82),
		/** Narrow tip / stout shoulder braced obliquely for focused pick-style strikes. */
		ANGLED_PICK("angled", 1.70, 0.90);

		public final String id;
		public final double leverBoost;
		public final double swingAlignment;

		Orientation(String id, double leverBoost, double swingAlignment) {
			this.id = id;
			this.leverBoost = leverBoost;
			this.swingAlignment = swingAlignment;
		}

		public static Orientation fromId(String id) {
			for (Orientation o : values()) {
				if (o.id.equals(id)) return o;
			}
			return INLINE;
		}
	}

	public boolean hafted = false;
	public Orientation orientation = Orientation.INLINE;
	public double handleLengthCm = 0.0;
	public double handleMassKg = 0.0;
	public double contactAreaCm2 = 0.0;
	public double socketFit = 0.0;       // 0..1 how well stone waist/flat mates with wood slot
	public double edgeClearance = 0.0;   // 0..1 how cleanly working edge projects free of shaft
	public String bindingMaterial = "none"; // plant_fiber, cordage, sinew, tendon
	public double bindingPlacement = 0.0; // 0..1 centering of lashing across contact collar
	public double bindingTension = 0.0;   // 0..1 tightness of lashing
	public double slipResistance = 0.0;   // 0..1 combined lock preventing head wobble/rotation

	public HaftData() {
	}

	public HaftData copy() {
		HaftData h = new HaftData();
		h.hafted = hafted;
		h.orientation = orientation;
		h.handleLengthCm = handleLengthCm;
		h.handleMassKg = handleMassKg;
		h.contactAreaCm2 = contactAreaCm2;
		h.socketFit = socketFit;
		h.edgeClearance = edgeClearance;
		h.bindingMaterial = bindingMaterial;
		h.bindingPlacement = bindingPlacement;
		h.bindingTension = bindingTension;
		h.slipResistance = slipResistance;
		return h;
	}

	public void attachHandle(Orientation orientation, double handleLengthCm, double handleMassKg,
							 VoxelRockGrid.GeometryMetrics metrics) {
		this.hafted = true;
		this.orientation = orientation;
		this.handleLengthCm = Math.max(20.0, Math.min(95.0, handleLengthCm));
		this.handleMassKg = Math.max(0.10, handleMassKg);
		this.contactAreaCm2 = evaluateContactArea(metrics, orientation);
		this.socketFit = evaluateSocketFit(metrics, orientation);
		this.edgeClearance = evaluateEdgeClearance(metrics, orientation);
		if ("none".equals(this.bindingMaterial) || this.bindingTension <= 0.05) {
			this.bindingMaterial = "friction";
			this.bindingPlacement = 0.45;
			this.bindingTension = 0.18;
		}
		recomputeSlipResistance();
	}

	/** Wraps cordage, sinew, tendon, or plant fiber around the stone-wood junction. */
	public void wrapBinding(String matId, double placementAccuracy) {
		if (!hafted) return;
		double matStrength = switch (matId) {
			case "sinew" -> 0.96;
			case "tendon" -> 0.92;
			case "cordage" -> 0.85;
			default -> 0.55; // raw plant fiber loosens faster
		};
		if ("friction".equals(bindingMaterial) || "none".equals(bindingMaterial)
				|| strengthOf(matId) >= strengthOf(bindingMaterial)) {
			bindingMaterial = matId;
		}
		bindingPlacement = Math.max(bindingPlacement, Math.max(0.2, Math.min(1.0, placementAccuracy)));
		bindingTension = Math.min(1.0, bindingTension + 0.48 * matStrength);
		recomputeSlipResistance();
	}

	private static double strengthOf(String matId) {
		return switch (matId) {
			case "sinew" -> 1.0;
			case "tendon" -> 0.95;
			case "cordage" -> 0.85;
			case "plant_fiber" -> 0.45;
			default -> 0.1;
		};
	}

	public void recomputeSlipResistance() {
		if (!hafted) {
			slipResistance = 0.0;
			return;
		}
		double matFactor = strengthOf(bindingMaterial);
		double areaFactor = Math.min(1.0, contactAreaCm2 / 14.0);
		slipResistance = Math.max(0.05, Math.min(1.0,
				(0.30 * socketFit + 0.25 * areaFactor + 0.25 * bindingPlacement + 0.20 * matFactor) * bindingTension));
	}

	/** Applies dynamic shock loosening under heavy blows; returns false if the head flies loose! */
	public boolean absorbImpactShock(double shockLoad) {
		if (!hafted) return true;
		double resistance = Math.max(0.20, strengthOf(bindingMaterial) * (0.5 + 0.5 * socketFit));
		double loss = (shockLoad / resistance) * 0.032;
		bindingTension = Math.max(0.0, bindingTension - loss);
		recomputeSlipResistance();
		if (bindingTension <= 0.01) {
			hafted = false;
			bindingMaterial = "none";
			return false;
		}
		return true;
	}

	public double effectiveMassKg(double stoneMassKg) {
		if (!hafted) return stoneMassKg;
		// Rotational leverage multiplies tip momentum according to handle length & stone seating
		double leverage = 1.0 + (handleLengthCm / 55.0) * (orientation.leverBoost - 0.55) * (0.45 + 0.55 * slipResistance);
		return stoneMassKg * leverage + handleMassKg * 0.35;
	}

	private static double evaluateContactArea(VoxelRockGrid.GeometryMetrics m, Orientation o) {
		double base = switch (o) {
			case INLINE -> m.widthCm() * Math.min(5.5, m.lengthCm() * 0.35);
			case PERPENDICULAR_CHOP, ANGLED_PICK -> m.thicknessCm() * Math.min(6.0, m.widthCm() * 0.55);
			case TRANSVERSE_ADZE -> m.widthCm() * Math.min(5.0, m.thicknessCm() * 0.70);
		};
		return Math.max(2.0, Math.min(28.0, base));
	}

	private static double evaluateSocketFit(VoxelRockGrid.GeometryMetrics m, Orientation o) {
		// Flat contact patch + reasonable stone mass proportions stabilize wooden sockets
		double flatSupport = Math.min(1.0, m.flatSurfaceAreaCm2() / 22.0);
		double aspect = switch (o) {
			case INLINE -> Math.min(1.0, m.lengthCm() / Math.max(2.0, m.widthCm() * 1.3));
			case PERPENDICULAR_CHOP -> Math.min(1.0, m.lengthCm() / Math.max(2.0, m.thicknessCm() * 1.4));
			case TRANSVERSE_ADZE -> Math.min(1.0, m.widthCm() / Math.max(2.0, m.thicknessCm() * 1.2));
			case ANGLED_PICK -> Math.min(1.0, (m.lengthCm() / Math.max(2.0, m.widthCm())) * 0.65 + 0.35);
		};
		return Math.max(0.18, Math.min(1.0, 0.45 * flatSupport + 0.40 * aspect + 0.15 * (1.0 - m.fractureDensity())));
	}

	private static double evaluateEdgeClearance(VoxelRockGrid.GeometryMetrics m, Orientation o) {
		if (m.continuousEdgeCm() < 1.5 && m.pointScore() < 0.25) return 0.20;
		return switch (o) {
			case INLINE -> Math.min(1.0, Math.max(0.35, (m.continuousEdgeCm() / 8.0) * 0.7 + m.pointScore() * 0.4));
			case PERPENDICULAR_CHOP -> Math.min(1.0, Math.max(0.30, (m.lengthCm() / 12.0) * 0.55 + (m.continuousEdgeCm() / 8.0) * 0.45));
			case TRANSVERSE_ADZE -> Math.min(1.0, Math.max(0.30, (m.widthCm() / 8.0) * 0.5 + (m.continuousEdgeCm() / 7.0) * 0.5));
			case ANGLED_PICK -> Math.min(1.0, Math.max(0.30, m.pointScore() * 0.7 + (m.lengthCm() / 14.0) * 0.3));
		};
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putBoolean("Hafted", hafted);
		if (!hafted) return t;
		t.putString("Ori", orientation.id);
		t.putDouble("LenCm", handleLengthCm);
		t.putDouble("MassKg", handleMassKg);
		t.putDouble("Area", contactAreaCm2);
		t.putDouble("Fit", socketFit);
		t.putDouble("Clear", edgeClearance);
		t.putString("BindMat", bindingMaterial);
		t.putDouble("BindPos", bindingPlacement);
		t.putDouble("BindTen", bindingTension);
		t.putDouble("SlipRes", slipResistance);
		return t;
	}

	public static HaftData fromNbt(NbtCompound t) {
		HaftData h = new HaftData();
		h.hafted = t.getBoolean("Hafted");
		if (!h.hafted) return h;
		h.orientation = Orientation.fromId(t.getString("Ori"));
		h.handleLengthCm = t.getDouble("LenCm");
		h.handleMassKg = t.getDouble("MassKg");
		h.contactAreaCm2 = t.getDouble("Area");
		h.socketFit = t.getDouble("Fit");
		h.edgeClearance = t.getDouble("Clear");
		h.bindingMaterial = t.contains("BindMat") ? t.getString("BindMat") : "plant_fiber";
		h.bindingPlacement = t.getDouble("BindPos");
		h.bindingTension = t.getDouble("BindTen");
		h.recomputeSlipResistance();
		return h;
	}
}
