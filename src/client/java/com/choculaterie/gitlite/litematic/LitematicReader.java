package com.choculaterie.gitlite.litematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Reads a {@code .litematic} file back into a dense {@link LitematicWriter.CaptureSnapshot} -
 * the write side's precise inverse, resolving every palette entry into a real {@link BlockState}
 * so the result can be pasted directly into a world via {@link #paste}.
 */
public final class LitematicReader {

	private LitematicReader() {}

	public static LitematicWriter.CaptureSnapshot readFromBytes(byte[] data) throws IOException {
		CompoundTag root;
		try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
			root = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
		}

		CompoundTag regions = root.getCompound("Regions").orElseThrow(() -> new IOException("Missing Regions"));
		if (regions.keySet().isEmpty()) throw new IOException("No regions found");
		CompoundTag region = regions.getCompoundOrEmpty(regions.keySet().iterator().next());

		CompoundTag sizeTag = region.getCompoundOrEmpty("Size");
		int sizeX = Math.abs(sizeTag.getInt("x").orElse(0));
		int sizeY = Math.abs(sizeTag.getInt("y").orElse(0));
		int sizeZ = Math.abs(sizeTag.getInt("z").orElse(0));

		ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int i = 0; i < paletteTag.size(); i++) {
			palette[i] = resolveBlockState(paletteTag.getCompound(i).orElse(new CompoundTag()));
		}

		long[] blockStates = region.getLongArray("BlockStates").orElseThrow(() -> new IOException("Missing BlockStates"));
		int totalBlocks = sizeX * sizeY * sizeZ;
		int bitsPerBlock = Math.max(2, (int) Math.ceil(Math.log(palette.length) / Math.log(2)));
		long mask = (1L << bitsPerBlock) - 1L;

		BlockState[] cells = new BlockState[totalBlocks];
		for (int i = 0; i < totalBlocks; i++) {
			int bitIndex = i * bitsPerBlock;
			int arrayIndex = bitIndex / 64;
			int bitOffset = bitIndex % 64;

			long value;
			if (bitOffset + bitsPerBlock <= 64) {
				value = (blockStates[arrayIndex] >>> bitOffset) & mask;
			} else {
				int bitsFromFirst = 64 - bitOffset;
				long firstPart = (blockStates[arrayIndex] >>> bitOffset) & ((1L << bitsFromFirst) - 1L);
				long secondPart = (arrayIndex + 1 < blockStates.length)
					? (blockStates[arrayIndex + 1] & (mask >>> bitsFromFirst))
					: 0L;
				value = firstPart | (secondPart << bitsFromFirst);
			}

			int paletteIndex = (int) value;
			cells[i] = (paletteIndex >= 0 && paletteIndex < palette.length) ? palette[paletteIndex] : Blocks.AIR.defaultBlockState();
		}

		return new LitematicWriter.CaptureSnapshot(sizeX, sizeY, sizeZ, cells);
	}

	private static BlockState resolveBlockState(CompoundTag entry) {
		String name = entry.getString("Name").orElse("minecraft:air");
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(name));
		BlockState state = block.defaultBlockState();

		CompoundTag properties = entry.getCompoundOrEmpty("Properties");
		for (String key : properties.keySet()) {
			String value = properties.getString(key).orElse(null);
			if (value != null) {
				state = applyProperty(state, block, key, value);
			}
		}
		return state;
	}

	private static BlockState applyProperty(BlockState state, Block block, String key, String value) {
		Property<?> property = block.getStateDefinition().getProperty(key);
		return property == null ? state : applyTyped(state, property, value);
	}

	private static <T extends Comparable<T>> BlockState applyTyped(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}

	/**
	 * Pastes a snapshot into the world with its minimum corner at {@code origin}. Must be called
	 * on the world's owning thread - the client thread for a {@code ClientLevel}, or via
	 * {@code MinecraftServer.execute(...)} for a singleplayer integrated server's
	 * {@code ServerLevel} - since this directly mutates block state.
	 */
	public static void paste(Level world, LitematicWriter.CaptureSnapshot snapshot, BlockPos origin) {
		int sizeX = snapshot.sizeX(), sizeY = snapshot.sizeY(), sizeZ = snapshot.sizeZ();
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					BlockState state = snapshot.cells()[y * sizeX * sizeZ + z * sizeX + x];
					world.setBlockAndUpdate(origin.offset(x, y, z), state);
				}
			}
		}
	}
}
