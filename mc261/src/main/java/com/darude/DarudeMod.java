package com.darude;

import com.darude.common.DarudeCommonBootstrap;
import com.darude.platform.v261.DarudePlatformAdapter261;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DarudeMod implements ModInitializer {
	public static final String MOD_ID = "darude";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		String versionBand = DarudeCommonBootstrap.initialize(new DarudePlatformAdapter261());
		LOGGER.info("Darude initialized: sandstorms, renewable sand and sand layers [{}]", versionBand);
	}
}
