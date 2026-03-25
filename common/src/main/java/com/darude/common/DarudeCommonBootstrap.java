package com.darude.common;

import com.darude.platform.DarudePlatformAdapter;

public final class DarudeCommonBootstrap {
	private DarudeCommonBootstrap() {
	}

	public static String initialize(DarudePlatformAdapter adapter) {
		adapter.initializeServer();
		return adapter.versionBand();
	}
}
