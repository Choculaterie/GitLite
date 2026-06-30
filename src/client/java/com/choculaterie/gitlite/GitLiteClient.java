package com.choculaterie.gitlite;

import com.choculaterie.gitlite.selection.ZoneOverlayRenderer;
import com.choculaterie.gitlite.selection.ZoneSelectionManager;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client-side entry point for GitLite.
 *
 * <p>GitLite integrates with the Choculaterie backend to give players a Git-like
 * version-control workflow for Litematica schematics directly inside Minecraft.
 * The mod injects a button into the Litematic Downloader screen (via
 * {@link com.choculaterie.gitlite.mixin.LitematicDownloaderScreenMixin}) and
 * exposes its own screens for repository browsing, account linking, and module
 * management.
 *
 * <p>Registers the zone-selection click listeners and live overlay renderer at init;
 * both are no-ops until a "Capture Zone" action arms them.
 */
public class GitLiteClient implements ClientModInitializer {

	/** Called by Fabric when the client is initialised. */
	@Override
	public void onInitializeClient() {
		ZoneSelectionManager.registerEvents();
		ZoneOverlayRenderer.register();
		GitLite.LOGGER.info("GitLite client initialized");
	}
}
