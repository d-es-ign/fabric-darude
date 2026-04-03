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
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

import java.util.function.Function;

public final class DarudeBlocks {
	public static final Block SAND_LAYER = registerBlock("sand_layer", settings -> new SandLayerBlock(
		settings
			.sounds(BlockSoundGroup.SAND)
			.nonOpaque()
			.pistonBehavior(PistonBehavior.DESTROY)
			.allowsSpawning((state, world, pos, type) -> {
				int layers = state.get(SandLayerBlock.LAYERS);
				return layers == 1 || layers >= 8;
			})
	), AbstractBlock.Settings.copy(Blocks.SAND));

	public static final Block PYRAMID = registerBlock("pyramid", settings -> new PyramidBlock(
		settings
			.nonOpaque()
	), AbstractBlock.Settings.copy(Blocks.SMOOTH_SANDSTONE));

	public static final Block FULL_PYRAMID = registerBlock("full_pyramid", settings -> new FullPyramidBlock(
		settings
			.nonOpaque()
	), AbstractBlock.Settings.copy(Blocks.SMOOTH_SANDSTONE));

	private DarudeBlocks() {
	}

	public static void initialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> {
			entries.add(SAND_LAYER);
			entries.add(PYRAMID);
			entries.add(FULL_PYRAMID);
		});
	}

	private static Block registerBlock(String name, Function<AbstractBlock.Settings, Block> blockFactory, AbstractBlock.Settings baseSettings) {
		Identifier id = Identifier.of(DarudeMod.MOD_ID, name);
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, id);
		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);
		Block block = blockFactory.apply(baseSettings.registryKey(blockKey));
		Registry.register(Registries.BLOCK, id, block);
		Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings().registryKey(itemKey)));
		return block;
	}
}
