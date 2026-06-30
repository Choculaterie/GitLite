package com.choculaterie.gitlite.litematic;

import com.choculaterie.util.LitematicParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures a world region into a brand-new, standard-format {@code .litematic} file - the
 * write-side counterpart to LD's read-only {@code LitematicParser}. The bit-packing here is the
 * precise mathematical inverse of {@code LitematicParser}'s decode loop: {@code bitsPerBlock =
 * max(2, ceil(log2(paletteSize)))}, each block's palette index OR'd into the {@code BlockStates}
 * long array at {@code i * bitsPerBlock}, split across two longs when it crosses a 64-bit
 * boundary.
 *
 * <p>Split into two phases on purpose: {@link #readSnapshot} touches the {@link Level} and must
 * only ever be called from the main/client thread, while {@link #writeToFile} and
 * {@link #toBlockDataList} operate purely on the already-collected snapshot and are safe to run
 * on a background thread.
 */
public final class LitematicWriter {

	/** Sanity cap mirroring LD's own preview limits (LitematicParser.MAX_PREVIEW_BLOCKS = 80,000). */
	private static final int MAX_CAPTURE_BLOCKS = 80_000;

	private LitematicWriter() {}

	/** A dense, already-collected snapshot of a captured region's blocks. */
	public record CaptureSnapshot(int sizeX, int sizeY, int sizeZ, BlockState[] cells) {}

	/**
	 * Reads every block state in the region between {@code pos1} and {@code pos2} (inclusive, any
	 * corner order) into a dense in-memory snapshot. Must be called on the main/client thread.
	 *
	 * @throws IllegalArgumentException if the selection exceeds {@link #MAX_CAPTURE_BLOCKS}
	 */
	public static CaptureSnapshot readSnapshot(Level world, BlockPos pos1, BlockPos pos2) {
		int minX = Math.min(pos1.getX(), pos2.getX());
		int minY = Math.min(pos1.getY(), pos2.getY());
		int minZ = Math.min(pos1.getZ(), pos2.getZ());
		int sizeX = Math.abs(pos2.getX() - pos1.getX()) + 1;
		int sizeY = Math.abs(pos2.getY() - pos1.getY()) + 1;
		int sizeZ = Math.abs(pos2.getZ() - pos1.getZ()) + 1;
		int totalBlocks = sizeX * sizeY * sizeZ;

		if (totalBlocks > MAX_CAPTURE_BLOCKS) {
			throw new IllegalArgumentException("Selection is too large (" + totalBlocks + " blocks, max " + MAX_CAPTURE_BLOCKS + ")");
		}

		BlockState[] cells = new BlockState[totalBlocks];
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					int i = y * sizeX * sizeZ + z * sizeX + x;
					cells[i] = world.getBlockState(new BlockPos(minX + x, minY + y, minZ + z));
				}
			}
		}
		return new CaptureSnapshot(sizeX, sizeY, sizeZ, cells);
	}

	/**
	 * Builds the sparse block list LD's {@link com.choculaterie.gui.widget.SchematicRenderer}
	 * expects for a 3D preview - air cells are skipped, matching how {@code LitematicParser}
	 * itself skips air when reading a file back for preview. Safe to call off the main thread.
	 */
	public static List<LitematicParser.BlockData> toBlockDataList(CaptureSnapshot snapshot) {
		List<LitematicParser.BlockData> blocks = new ArrayList<>();
		int sizeX = snapshot.sizeX(), sizeY = snapshot.sizeY(), sizeZ = snapshot.sizeZ();
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					BlockState state = snapshot.cells()[y * sizeX * sizeZ + z * sizeX + x];
					if (state.isAir()) continue;
					Map<String, String> properties = new LinkedHashMap<>();
					state.getValues().forEach(value -> properties.put(value.property().getName(), value.valueName()));
					String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
					blocks.add(new LitematicParser.BlockData(x, y, z, blockId, properties));
				}
			}
		}
		return blocks;
	}

	/**
	 * Writes a snapshot to a new {@code .litematic} file under
	 * {@code <gameDir>/gitlite/captures/}. Writes the full standard litematic root structure
	 * (Metadata + per-region Position) so the file is portable to real Litematica installs, not
	 * just round-trippable through this mod's own narrower reader. Safe to call off the main
	 * thread - touches only the snapshot and the filesystem.
	 *
	 * @return the written file
	 * @throws IOException if writing the file fails
	 */
	public static File writeToFile(CaptureSnapshot snapshot, String name, String author, String description) throws IOException {
		int sizeX = snapshot.sizeX(), sizeY = snapshot.sizeY(), sizeZ = snapshot.sizeZ();
		int totalBlocks = sizeX * sizeY * sizeZ;

		Map<BlockState, Integer> paletteIndices = new LinkedHashMap<>();
		int[] indexPerBlock = new int[totalBlocks];
		for (int i = 0; i < totalBlocks; i++) {
			BlockState state = snapshot.cells()[i];
			indexPerBlock[i] = paletteIndices.computeIfAbsent(state, s -> paletteIndices.size());
		}

		ListTag paletteTag = new ListTag();
		for (BlockState state : paletteIndices.keySet()) {
			paletteTag.add(blockStateToTag(state));
		}

		long[] blockStates = packBlockStates(indexPerBlock, paletteIndices.size());

		CompoundTag sizeTag = new CompoundTag();
		sizeTag.putInt("x", sizeX);
		sizeTag.putInt("y", sizeY);
		sizeTag.putInt("z", sizeZ);

		CompoundTag positionTag = new CompoundTag();
		positionTag.putInt("x", 0);
		positionTag.putInt("y", 0);
		positionTag.putInt("z", 0);

		CompoundTag region = new CompoundTag();
		region.put("Position", positionTag);
		region.put("Size", sizeTag);
		region.put("BlockStatePalette", paletteTag);
		region.put("BlockStates", new LongArrayTag(blockStates));

		CompoundTag regions = new CompoundTag();
		regions.put("main", region);

		long now = System.currentTimeMillis();
		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", name);
		metadata.putString("Author", author);
		metadata.putString("Description", description == null ? "" : description);
		metadata.putInt("RegionCount", 1);
		metadata.putInt("TotalVolume", totalBlocks);
		metadata.putInt("TotalBlocks", totalBlocks);
		metadata.putLong("TimeCreated", now);
		metadata.putLong("TimeModified", now);
		metadata.put("EnclosingSize", sizeTag);

		CompoundTag root = new CompoundTag();
		root.putInt("Version", 6);
		root.put("Metadata", metadata);
		root.put("Regions", regions);

		File captureDir = FabricLoader.getInstance().getGameDir().resolve("gitlite/captures").toFile();
		Files.createDirectories(captureDir.toPath());
		File out = new File(captureDir, "capture-" + now + ".litematic");
		try (FileOutputStream fos = new FileOutputStream(out)) {
			NbtIo.writeCompressed(root, fos);
		}
		return out;
	}

	private static CompoundTag blockStateToTag(BlockState state) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());

		CompoundTag properties = new CompoundTag();
		state.getValues().forEach(value -> properties.putString(value.property().getName(), value.valueName()));
		if (!properties.isEmpty()) {
			tag.put("Properties", properties);
		}
		return tag;
	}

	/** Precise inverse of LitematicParser's decode loop - see the class-level doc for the formula. */
	private static long[] packBlockStates(int[] indexPerBlock, int paletteSize) {
		int bitsPerBlock = Math.max(2, (int) Math.ceil(Math.log(paletteSize) / Math.log(2)));
		int totalBlocks = indexPerBlock.length;
		long[] blockStates = new long[(int) Math.ceil(totalBlocks * (long) bitsPerBlock / 64.0)];

		for (int i = 0; i < totalBlocks; i++) {
			long value = indexPerBlock[i];
			int bitIndex = i * bitsPerBlock;
			int arrayIndex = bitIndex / 64;
			int bitOffset = bitIndex % 64;

			blockStates[arrayIndex] |= value << bitOffset;

			if (bitOffset + bitsPerBlock > 64) {
				int bitsWrittenToFirst = 64 - bitOffset;
				blockStates[arrayIndex + 1] |= value >> bitsWrittenToFirst;
			}
		}

		return blockStates;
	}
}
