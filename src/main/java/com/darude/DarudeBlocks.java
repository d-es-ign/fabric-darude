package com.darude;

import com.darude.block.FullPyramidBlock;
import com.darude.block.SandLayerBlock;
import com.darude.block.PyramidBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

public final class DarudeBlocks {
	public static final Block SAND_LAYER = registerBlock("sand_layer", new SandLayerBlock(
		AbstractBlock.Settings
			.copy(Blocks.SAND)
			.sounds(BlockSoundGroup.SAND)
			.nonOpaque()
			.pistonBehavior(PistonBehavior.DESTROY)
			.allowsSpawning((state, world, pos, type) -> {
				int layers = state.get(SandLayerBlock.LAYERS);
				return layers == 1 || layers >= 8;
			})
	));

	public static final Block PYRAMID = registerBlock("pyramid", new PyramidBlock(
		AbstractBlock.Settings
			.copy(Blocks.SMOOTH_SANDSTONE)
			.nonOpaque()
	));

	public static final Block FULL_PYRAMID = registerBlock("full_pyramid", new FullPyramidBlock(
		AbstractBlock.Settings
			.copy(Blocks.SMOOTH_SANDSTONE)
			.nonOpaque()
	));

	private DarudeBlocks() {
	}

	public static void initialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> entries.add(SAND_LAYER));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> entries.add(PYRAMID));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> entries.add(FULL_PYRAMID));
	}

	private static Block registerBlock(String name, Block block) {
		Identifier id = Identifier.of(DarudeMod.MOD_ID, name);
		Registry.register(Registries.BLOCK, id, block);
		Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
		return block;
	}
}
