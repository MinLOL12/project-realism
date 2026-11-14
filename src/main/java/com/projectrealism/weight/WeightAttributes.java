package com.projectrealism.weight;

import com.projectrealism.ProjectRealism;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

/**
 * The attribute plumbing. Project Realism changes movement speed with a single named modifier rather
 * than by writing the base value, so it stacks correctly with beacons, potions, Soul Speed, Depth
 * Strider and every other mod that touches the same attribute — and so removing it can never leave a
 * player permanently crippled.
 */
public final class WeightAttributes {
	/** Identifier of the modifier Project Realism owns exclusively. */
	public static final Identifier ENCUMBRANCE_MODIFIER_ID = ProjectRealism.id("encumbrance");

	private WeightAttributes() {
	}

	public static void register() {
		// Movement speed is a vanilla attribute; nothing to register. Kept as a named step so the
		// initializer reads as a list of stages and any future custom attributes have an obvious home.
	}

	/**
	 * Applies a speed multiplier in the range (0.0, 1.0]. A multiplier of 1.0 removes the modifier
	 * entirely, which keeps the attribute NBT clean for unburdened players.
	 */
	public static void apply(PlayerEntity player, double multiplier) {
		EntityAttributeInstance instance = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
		if (instance == null) {
			return;
		}

		double clamped = Math.max(0.01, Math.min(1.0, multiplier));
		double delta = clamped - 1.0;

		if (delta >= -1.0e-6) {
			if (instance.hasModifier(ENCUMBRANCE_MODIFIER_ID)) {
				instance.removeModifier(ENCUMBRANCE_MODIFIER_ID);
			}
			return;
		}

		instance.overwritePersistentModifier(new EntityAttributeModifier(
				ENCUMBRANCE_MODIFIER_ID,
				delta,
				EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/** Drops the modifier. Used on logout, on config changes, and in creative mode. */
	public static void clear(PlayerEntity player) {
		apply(player, 1.0);
	}
}
