/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.fluid.FluidState;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.XRayHack;

@Mixin(RenderLayers.class)
public abstract class RenderLayersMixin
{
	@ModifyReturnValue(at = @At("RETURN"),
		method = "getBlockLayer(Lnet/minecraft/block/BlockState;)Lnet/minecraft/client/render/RenderLayer;")
	private static RenderLayer onGetBlockLayer(RenderLayer original,
		BlockState state)
	{
		return getXRayLayer(original, state.getBlock());
	}

	@ModifyReturnValue(at = @At("RETURN"),
		method = "getFluidLayer(Lnet/minecraft/fluid/FluidState;)Lnet/minecraft/client/render/RenderLayer;")
	private static RenderLayer onGetFluidLayer(RenderLayer original,
		FluidState state)
	{
		return getXRayLayer(original, state.getBlockState().getBlock());
	}

	private static RenderLayer getXRayLayer(RenderLayer original, Block block)
	{
		XRayHack xray = WurstClient.INSTANCE.getHax().xRayHack;
		if(xray.isSodiumSkeletonMode())
			return xray.isSelectedForXRay(block) ? RenderLayer.getTranslucent()
				: RenderLayer.getCutoutMipped();

		if(xray.isOpacityMode())
			return RenderLayer.getTranslucent();

		return original;
	}
}
