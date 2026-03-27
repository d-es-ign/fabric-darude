package com.darude;

import com.darude.block.FullPyramidBlock;
import com.darude.block.SandLayerBlock;
import com.darude.block.PyramidBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

public final class DarudeBlocks {
	private static final ResourceKey<CreativeModeTab> NATURAL_TAB = ResourceKey.create(
		Registries.CREATIVE_MODE_TAB,
		Identifier.fromNamespaceAndPath("minecraft", "natural_blocks")
	);

	public static final Block SAND_LAYER = registerBlock("sand_layer", new SandLayerBlock(
		BlockBehaviour.Properties
			.ofFullCopy(Blocks.SAND)
			.sound(SoundType.SAND)
			.noOcclusion()
			.pushReaction(PushReaction.DESTROY)
			.isValidSpawn((state, world, pos, type) -> {
				int layers = state.getValue(SandLayerBlock.LAYERS);
				return layers == 1 || layers >= 8;
			})
	));

	public static final Block PYRAMID = registerBlock("pyramid", new PyramidBlock(
		BlockBehaviour.Properties
			.ofFullCopy(Blocks.SMOOTH_SANDSTONE)
			.noOcclusion()
	));

	public static final Block FULL_PYRAMID = registerBlock("full_pyramid", new FullPyramidBlock(
		BlockBehaviour.Properties
			.ofFullCopy(Blocks.SMOOTH_SANDSTONE)
			.noOcclusion()
	));

	private DarudeBlocks() {
	}

	public static void initialize() {
		ItemGroupEvents.modifyEntriesEvent(NATURAL_TAB).register(entries -> {
			entries.accept(SAND_LAYER);
			entries.accept(PYRAMID);
			entries.accept(FULL_PYRAMID);
		});
	}

	private static Block registerBlock(String name, Block block) {
		Identifier id = Identifier.fromNamespaceAndPath(DarudeMod.MOD_ID, name);
		Registry.register(BuiltInRegistries.BLOCK, id, block);
		Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties()));
		return block;
	}
}
