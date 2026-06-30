package com.choculaterie.gitlite.selection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks region selection for GitLite repos: left-click sets position 1, right-click sets
 * position 2, both reported via in-game chat. While {@link #armed}, the normal block-break/use
 * actions for those clicks are suppressed.
 *
 * <p>Once both positions are set and saved via {@link #saveZone}, the zone is persisted per
 * repository (like a Litematica placement, not a one-shot selection) so it survives screen
 * closes and game restarts in {@code config/gitlite-zones.json} - pushing from a repo with a
 * saved zone re-captures that same region's current contents without needing to reselect it.
 */
public final class ZoneSelectionManager {

	private static final String STORAGE_FILE = "gitlite-zones.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** A saved, persistent zone bound to a repository. */
	public record SavedZone(int x1, int y1, int z1, int x2, int y2, int z2) {
		public BlockPos pos1() {
			return new BlockPos(x1, y1, z1);
		}

		public BlockPos pos2() {
			return new BlockPos(x2, y2, z2);
		}

		static SavedZone of(BlockPos pos1, BlockPos pos2) {
			return new SavedZone(pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX(), pos2.getY(), pos2.getZ());
		}
	}

	private static final Map<String, SavedZone> savedZones = new HashMap<>();
	private static String activeRepoId = null;
	private static boolean loaded = false;

	// In-progress selection state, for defining/redefining a zone.
	private static boolean armed = false;
	private static BlockPos pos1 = null;
	private static BlockPos pos2 = null;
	private static Runnable onBothPositionsSet;

	private ZoneSelectionManager() {}

	// -------------------------------------------------------------------------
	// Click events
	// -------------------------------------------------------------------------

	/** Registers the left/right click listeners once at client init. No-ops while not armed. */
	public static void registerEvents() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (!armed || !world.isClientSide()) return InteractionResult.PASS;
			if (pos.equals(pos1)) return InteractionResult.FAIL; // same click reported twice by vanilla; ignore the repeat
			pos1 = pos;
			if (player instanceof LocalPlayer local) {
				local.sendSystemMessage(Component.literal("Position 1 set: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"));
			}
			notifyIfReady();
			return InteractionResult.FAIL;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!armed || !world.isClientSide()) return InteractionResult.PASS;
			BlockPos pos = hitResult.getBlockPos();
			if (pos.equals(pos2)) return InteractionResult.FAIL;
			pos2 = pos;
			if (player instanceof LocalPlayer local) {
				local.sendSystemMessage(Component.literal("Position 2 set: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"));
			}
			notifyIfReady();
			return InteractionResult.FAIL;
		});
	}

	private static void notifyIfReady() {
		if (pos1 != null && pos2 != null && onBothPositionsSet != null) {
			onBothPositionsSet.run();
		}
	}

	// -------------------------------------------------------------------------
	// In-progress selection (defining/redefining a zone)
	// -------------------------------------------------------------------------

	/**
	 * Arms the tool: world clicks now set positions instead of breaking/using blocks.
	 *
	 * @param onBothSet called as soon as both positions are set, so the caller can reopen
	 *                   its confirmation screen
	 */
	public static void arm(Runnable onBothSet) {
		armed = true;
		pos1 = null;
		pos2 = null;
		onBothPositionsSet = onBothSet;
	}

	/** Cancels an in-progress selection. Does not affect any already-saved zone. */
	public static void disarm() {
		armed = false;
		pos1 = null;
		pos2 = null;
		onBothPositionsSet = null;
	}

	public static boolean isArmed() {
		return armed;
	}

	public static BlockPos getPos1() {
		return pos1;
	}

	public static BlockPos getPos2() {
		return pos2;
	}

	public static int getSizeX() {
		return pos1 == null || pos2 == null ? 0 : Math.abs(pos2.getX() - pos1.getX()) + 1;
	}

	public static int getSizeY() {
		return pos1 == null || pos2 == null ? 0 : Math.abs(pos2.getY() - pos1.getY()) + 1;
	}

	public static int getSizeZ() {
		return pos1 == null || pos2 == null ? 0 : Math.abs(pos2.getZ() - pos1.getZ()) + 1;
	}

	// -------------------------------------------------------------------------
	// Persistent per-repo zones
	// -------------------------------------------------------------------------

	/** Marks which repo's saved zone the overlay should keep showing; persisted across sessions. */
	public static void setActiveRepo(String repoId) {
		ensureLoaded();
		if (Objects.equals(activeRepoId, repoId)) return;
		activeRepoId = repoId;
		persist();
	}

	public static String getActiveRepo() {
		ensureLoaded();
		return activeRepoId;
	}

	public static boolean hasSavedZone(String repoId) {
		ensureLoaded();
		return savedZones.containsKey(repoId);
	}

	public static SavedZone getSavedZone(String repoId) {
		ensureLoaded();
		return savedZones.get(repoId);
	}

	/** Saves the current in-progress selection as the persistent zone for {@code repoId}. */
	public static void saveZone(String repoId, BlockPos pos1, BlockPos pos2) {
		ensureLoaded();
		savedZones.put(repoId, SavedZone.of(pos1, pos2));
		activeRepoId = repoId;
		persist();
	}

	public static void clearSavedZone(String repoId) {
		ensureLoaded();
		savedZones.remove(repoId);
		persist();
	}

	/** Caches a zone pulled from the cloud (e.g. on repo load from another install). Local-only; does not re-upload. */
	public static void applyCloudZone(String repoId, int x1, int y1, int z1, int x2, int y2, int z2) {
		ensureLoaded();
		savedZones.put(repoId, new SavedZone(x1, y1, z1, x2, y2, z2));
		persist();
	}

	// -------------------------------------------------------------------------
	// Persistence
	// -------------------------------------------------------------------------

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		File file = getStorageFile();
		if (!file.exists()) return;
		try (FileReader reader = new FileReader(file)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);
			if (root == null) return;
			if (root.has("activeRepoId") && !root.get("activeRepoId").isJsonNull()) {
				activeRepoId = root.get("activeRepoId").getAsString();
			}
			if (root.has("zones")) {
				JsonObject zones = root.getAsJsonObject("zones");
				for (String repoId : zones.keySet()) {
					savedZones.put(repoId, GSON.fromJson(zones.get(repoId), SavedZone.class));
				}
			}
		} catch (Exception ignored) {
			// Corrupt or unreadable file: start fresh rather than crash the client.
		}
	}

	private static synchronized void persist() {
		try {
			File file = getStorageFile();
			File parent = file.getParentFile();
			if (parent != null) parent.mkdirs();

			JsonObject root = new JsonObject();
			if (activeRepoId != null) root.addProperty("activeRepoId", activeRepoId);
			JsonObject zones = new JsonObject();
			for (Map.Entry<String, SavedZone> entry : savedZones.entrySet()) {
				zones.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
			}
			root.add("zones", zones);

			try (FileWriter writer = new FileWriter(file)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException ignored) {
			// Best-effort persistence; a failed save just means the zone won't survive a restart.
		}
	}

	private static File getStorageFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(STORAGE_FILE).toFile();
	}
}
