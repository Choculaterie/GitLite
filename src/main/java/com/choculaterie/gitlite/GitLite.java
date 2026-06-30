package com.choculaterie.gitlite;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitLite implements ModInitializer {
	public static final String MOD_ID = "gitlite";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("GitLite mod initialized");
	}
}
