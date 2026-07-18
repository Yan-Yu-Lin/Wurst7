/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.client.render.RenderLayer;
import net.wurstclient.WurstClient;
import net.wurstclient.util.SodiumSkeletonRenderState;
import net.wurstclient.util.SodiumSkeletonRenderState.Phase;

@Pseudo
@Mixin(targets = {
	"me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer"},
	remap = false)
public abstract class SodiumWorldRendererMixin
{
	@Shadow
	private RenderSectionManager renderSectionManager;

	@Inject(at = @At("HEAD"), method = "drawChunkLayer", cancellable = true,
		require = 0)
	private void onDrawChunkLayer(RenderLayer renderLayer,
		ChunkRenderMatrices matrices, double x, double y, double z,
		CallbackInfo ci)
	{
		SodiumSkeletonRenderState.markWorldRendererHookPresent();
		if(!WurstClient.INSTANCE.getHax().xRayHack.isSodiumSkeletonMode())
			return;

		if(renderLayer == RenderLayer.getSolid())
		{
			render(Phase.DEPTH, matrices, x, y, z);
			render(Phase.LINES, matrices, x, y, z);
			ci.cancel();
			return;
		}

		if(renderLayer == RenderLayer.getTranslucent())
		{
			SodiumSkeletonRenderState.run(Phase.ORES,
				() -> renderSectionManager.renderLayer(matrices,
					DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z));
			ci.cancel();
		}
	}

	private void render(Phase phase, ChunkRenderMatrices matrices, double x,
		double y, double z)
	{
		SodiumSkeletonRenderState.run(phase,
			() -> renderSectionManager.renderLayer(matrices,
				DefaultTerrainRenderPasses.CUTOUT, x, y, z));
	}
}
