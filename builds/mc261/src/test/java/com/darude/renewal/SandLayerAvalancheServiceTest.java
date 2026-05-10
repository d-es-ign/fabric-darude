package com.darude.renewal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandLayerAvalancheServiceTest {
	@Test
	void normalizeColumnStateCarriesFullBlocksFromSetHeight() {
		assertEquals(2, SandLayerAvalancheService.normalizedStableSandBlocks(1, 18));
		assertEquals(2, SandLayerAvalancheService.normalizedActiveHeight(1, 18));
	}

	@Test
	void normalizeColumnStateCarriesFullBlocksFromTransferredLayers() {
		assertEquals(2, SandLayerAvalancheService.normalizedStableSandBlocks(1, 17));
		assertEquals(1, SandLayerAvalancheService.normalizedActiveHeight(1, 17));
	}
}
