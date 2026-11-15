package com.projectrealism.material;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Holds a physical {@link HideSurface} stretched on a Scraping Frame and handles targeted
 * spatial scraping strokes, fat/waste recovery, tool wear, and damp/dry sound feedback.
 */
public class ScrapingFrameBlockEntity extends BlockEntity {
	@Nullable
	private HideSurface hide = null;
	private double lastStrokeU = 0.5;
	private double lastStrokeV = 0.5;
	private long lastStrokeMs = 0L;

	public ScrapingFrameBlockEntity(BlockPos pos, BlockState state) {
		super(MaterialBlocks.SCRAPING_FRAME_ENTITY, pos, state);
	}

	public boolean hasHide() {
		return hide != null;
	}

	@Nullable
	public HideSurface hide() {
		return hide;
	}

	public void mountHide(ItemStack stack, PlayerEntity player) {
		if (hide != null || !stack.isOf(MaterialItems.RAW_HIDE)) return;
		hide = RawHideItem.readSurface(stack);
		if (!player.isCreative()) {
			stack.decrement(1);
		}
		sync();
	}

	public void takeHide(PlayerEntity player, World world, BlockPos pos) {
		if (hide == null) return;
		ItemStack item = RawHideItem.create(hide);
		hide = null;
		if (!player.getInventory().insertStack(item)) {
			Block.dropStack(world, pos, item);
		}
		sync();
	}

	public void scrapeAt(PlayerEntity player, Hand hand, ItemStack scraper, double u, double v,
						 World world, BlockPos pos) {
		if (hide == null) return;
		long now = System.currentTimeMillis();
		double sweep = (now - lastStrokeMs < 1500L) ? Math.hypot(u - lastStrokeU, v - lastStrokeV) : 0.08;
		lastStrokeU = u;
		lastStrokeV = v;
		lastStrokeMs = now;

		// Player pitch controls blade angle relative to angled frame; straight down = steep gouge!
		double pitchAbs = Math.abs(player.getPitch());
		double angleDeg = Math.max(12.0, Math.min(80.0, pitchAbs * 0.95));
		double sharpness = MaterialItems.StoneScraperItem.getSharpness(scraper) / 100.0;
		double pressure = player.isSprinting() ? 1.45 : (player.isSneaking() ? 0.72 : 1.0);

		HideSurface.ScrapeOutcome out = hide.scrapeAt(u, v, sweep, angleDeg, sharpness, pressure);

		if (world instanceof ServerWorld sw) {
			// Distinct acoustic feedback: wet hide sounds soft/squishy, dry hide rasps harshly!
			if (out.wetHide) {
				sw.playSound(null, pos, SoundEvents.BLOCK_SLIME_BLOCK_STEP, SoundCategory.BLOCKS,
						0.55f, (float) (0.95 + sharpness * 0.25));
				sw.spawnParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 0.65, pos.getZ() + 0.5,
						3, 0.2, 0.15, 0.2, 0.01);
			} else {
				sw.playSound(null, pos, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS,
						0.45f, 1.35f);
				sw.spawnParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.65, pos.getZ() + 0.5,
						3, 0.2, 0.15, 0.2, 0.02);
			}

			if (out.toreHole) {
				sw.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.7f, 0.7f);
				player.sendMessage(Text.translatable("project_realism.scrape.tore").formatted(Formatting.RED), true);
			} else if (out.inflictedDamage > 0.03) {
				player.sendMessage(Text.translatable("project_realism.scrape.overscraped").formatted(Formatting.YELLOW), true);
			}

			// Drop physical waste tallow/fat scrapings whenever accumulated
			while (hide.scrapedFatWasteKg >= 0.28) {
				hide.scrapedFatWasteKg -= 0.28;
				Block.dropStack(sw, pos.up(), new ItemStack(MaterialItems.HIDE_SCRAP, 1));
			}

			// Wear scraper edge slightly on each stroke
			if (!player.isCreative()) {
				int curS = MaterialItems.StoneScraperItem.getSharpness(scraper);
				int loss = out.wetHide ? 2 : 5;
				MaterialItems.StoneScraperItem.setSharpness(scraper, curS - loss);
				scraper.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
			}
		}
		sync();
	}

	public Text statusText() {
		if (hide == null) return Text.empty();
		int clean = (int) Math.round(hide.averageCleanliness() * 100.0);
		int dmg = (int) Math.round(hide.averageDamage() * 100.0);
		int moist = (int) Math.round(hide.averageMoisture() * 100.0);
		return Text.translatable("project_realism.scrape.status", hide.animal.id, clean, dmg, moist)
				.formatted(clean >= 85 ? Formatting.GREEN : Formatting.GOLD);
	}

	public void dropContents(World world, BlockPos pos) {
		if (hide != null) {
			Block.dropStack(world, pos, RawHideItem.create(hide));
			hide = null;
		}
	}

	private void sync() {
		markDirty();
		if (world != null && !world.isClient) {
			BlockState st = getCachedState();
			world.updateListeners(pos, st, st, Block.NOTIFY_LISTENERS);
		}
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.writeNbt(nbt, registryLookup);
		if (hide != null) {
			nbt.put("Hide", hide.toNbt());
		}
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		hide = nbt.contains("Hide") ? HideSurface.fromNbt(nbt.getCompound("Hide")) : null;
	}

	@Nullable
	@Override
	public Packet<ClientPlayPacketListener> toUpdatePacket() {
		return BlockEntityUpdateS2CPacket.create(this);
	}

	@Override
	public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
		return createNbt(registryLookup);
	}
}
