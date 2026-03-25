package com.darude.platform.v261;

import com.darude.DarudeBlocks;
import com.darude.platform.DarudePlatformAdapter;
import com.darude.worldgen.SandLayerChunkGeneration;
import com.darude.worldgen.SandLayerGenerationConfig;

public final class DarudePlatformAdapter261 implements DarudePlatformAdapter {
	@Override
	public void initializeServer() {
		DarudeBlocks.initialize();
		SandLayerGenerationConfig.registerReloadListener();
		SandLayerChunkGeneration.register();
	}

	@Override
	public String versionBand() {
		return "26.1";
	}
}
