package com.darude.platform.v261;

import com.darude.DarudeBlocks;
import com.darude.platform.DarudePlatformAdapter;

public final class DarudePlatformAdapter261 implements DarudePlatformAdapter {
	@Override
	public void initializeServer() {
		DarudeBlocks.initialize();
	}

	@Override
	public String versionBand() {
		return "26.1";
	}
}
