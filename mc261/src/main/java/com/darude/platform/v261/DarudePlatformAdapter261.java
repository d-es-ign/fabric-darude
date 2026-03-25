package com.darude.platform.v261;

import com.darude.platform.DarudePlatformAdapter;

public final class DarudePlatformAdapter261 implements DarudePlatformAdapter {
	@Override
	public void initializeServer() {
		// 26.1+ wiring is isolated from shared-mc until its APIs are fully ported.
	}

	@Override
	public String versionBand() {
		return "26.1";
	}
}
