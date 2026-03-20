package com.darude;

import com.darude.block.SandLayerBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSoundGroup;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class DarudeBlocks {
	public static final Block SAND_LAYER = registerBlock("sand_layer", new SandLayerBlock(
		AbstractBlock.Settings
			.copy(Blocks.SAND)
			.sounds(BlockSoundGroup.SAND)
			.nonOpaque()
	));

	private DarudeBlocks() {
	}

	public static void initialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> entries.add(SAND_LAYER));
	}

	private static Block registerBlock(String name, Block block) {
		Identifier id = Identifier.of(DarudeMod.MOD_ID, name);
		Registry.register(Registries.BLOCK, id, block);
		Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
		return block;
	}
}
