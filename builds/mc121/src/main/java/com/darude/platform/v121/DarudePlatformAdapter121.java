package com.darude.platform.v121;

import com.darude.DarudeBlocks;
import com.darude.platform.DarudePlatformAdapter;
import com.darude.worldgen.SandLayerChunkGeneration;
import com.darude.worldgen.SandLayerGenerationConfig;

public final class DarudePlatformAdapter121 implements DarudePlatformAdapter {
	@Override
	public void initializeServer() {
		DarudeBlocks.initialize();
		SandLayerGenerationConfig.registerReloadListener();
		SandLayerChunkGeneration.register();
	}

	@Override
	public String versionBand() {
		return "1.21.x";
	}
}
