package com.choculaterie.gitlite.config;

import com.choculaterie.gitlite.util.CryptoUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Singleton that persists GitLite's user configuration to
 * {@code <config-dir>/gitlite-settings.json}.
 *
 * <p>Currently the only setting is the API key used to authenticate against the
 * Choculaterie backend. The key is stored AES-encrypted via {@link CryptoUtils};
 * a legacy plaintext {@code "apiKey"} field is read as a fallback but is immediately
 * migrated to the encrypted form on the next {@link #setApiKey} call.
 *
 * <p>The singleton is lazily initialised on the first call to {@link #getInstance()}
 * and is not thread-safe - all calls must be made from the game thread.
 */
public class GitLiteSettings {

	private static final String CONFIG_FILE = "gitlite-settings.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static GitLiteSettings INSTANCE;

	/** In-memory representation of the JSON config; mutated and flushed by setters. */
	private final JsonObject config;

	private GitLiteSettings() {
		this.config = loadConfig();
	}

	// -------------------------------------------------------------------------
	// Singleton access
	// -------------------------------------------------------------------------

	/**
	 * Returns the singleton instance, creating and loading it from disk on first access.
	 *
	 * @return the shared {@code GitLiteSettings} instance
	 */
	public static GitLiteSettings getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new GitLiteSettings();
		}
		return INSTANCE;
	}

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Returns the stored API key, decrypting it if necessary.
	 *
	 * <p>Falls back to a legacy plaintext {@code "apiKey"} field if no encrypted
	 * key is present (supports configs written by older versions).
	 *
	 * @return the API key, or an empty string if none is configured
	 */
	public String getApiKey() {
		if (config.has("encryptedApiKey")) {
			try {
				return CryptoUtils.decrypt(config.get("encryptedApiKey").getAsString());
			} catch (Exception e) {
				return "";
			}
		}
		// Legacy plaintext fallback for configs written before encryption was added.
		if (config.has("apiKey")) {
			return config.get("apiKey").getAsString();
		}
		return "";
	}

	/** Returns {@code true} if a non-empty API key is stored. */
	public boolean hasApiKey() {
		String key = getApiKey();
		return key != null && !key.isEmpty();
	}

	/**
	 * Stores a new API key, encrypting it before writing to disk.
	 *
	 * <p>Any legacy plaintext {@code "apiKey"} field is removed. Passing {@code null}
	 * or an empty string clears the stored key entirely.
	 *
	 * @param apiKey the key to store, or {@code null}/{@code ""} to remove it
	 */
	public void setApiKey(String apiKey) {
		config.remove("apiKey"); // remove legacy plaintext if present
		if (apiKey == null || apiKey.isEmpty()) {
			config.remove("encryptedApiKey");
		} else {
			try {
				config.addProperty("encryptedApiKey", CryptoUtils.encrypt(apiKey));
			} catch (Exception e) {
				// Encryption unavailable on this JVM; fall back to plaintext rather than losing the key.
				System.err.println("Failed to encrypt API key, storing plaintext: " + e.getMessage());
				config.addProperty("apiKey", apiKey);
			}
		}
		save();
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	/** Reads the JSON config file from disk, returning an empty object if missing or unreadable. */
	private JsonObject loadConfig() {
		File configFile = getConfigFile();
		if (!configFile.exists()) {
			return new JsonObject();
		}
		try (FileReader reader = new FileReader(configFile)) {
			JsonObject json = GSON.fromJson(reader, JsonObject.class);
			return json != null ? json : new JsonObject();
		} catch (IOException e) {
			System.err.println("Failed to load GitLite settings: " + e.getMessage());
			return new JsonObject();
		}
	}

	private File getConfigFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE).toFile();
	}

	/** Writes the current in-memory config back to disk, creating parent directories if needed. */
	private void save() {
		try {
			File configFile = getConfigFile();
			File parentDir = configFile.getParentFile();
			if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
				System.err.println("Failed to create GitLite config directory");
				return;
			}
			try (FileWriter writer = new FileWriter(configFile)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			System.err.println("Failed to save GitLite settings: " + e.getMessage());
		}
	}
}
