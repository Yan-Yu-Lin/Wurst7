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
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.XRayHack;
import net.wurstclient.util.SkeletonPostRenderer;
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

	@Inject(at = @At("RETURN"), method = "<init>", require = 0)
	private void onConstructed(MinecraftClient client, CallbackInfo ci)
	{
		SodiumSkeletonRenderState.markWorldRendererHookPresent();
	}

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
			// The skeleton grid is deliberately deferred to the translucent
			// layer so that it composites on top of the through-wall ores
			// instead of being painted over by them.
			ci.cancel();
			return;
		}

		if(renderLayer == RenderLayer.getTranslucent())
		{
			SodiumSkeletonRenderState.run(Phase.ORES,
				() -> renderSectionManager.renderLayer(matrices,
					DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z));

			XRayHack xRay = WurstClient.INSTANCE.getHax().xRayHack;
			CameraTransform camera = new CameraTransform(x, y, z);
			boolean rendered = SkeletonPostRenderer.render(matrices.projection(),
				matrices.modelView(), camera.intX, camera.intY, camera.intZ,
				camera.fracX, camera.fracY, camera.fracZ,
				() -> renderOffscreenDepth(matrices, x, y, z),
				xRay.getSkeletonColor(), xRay.getSkeletonLineWidth(),
				xRay.getSkeletonOutline());
			if(!rendered)
				renderFallback(matrices, x, y, z);

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

	/**
	 * Renders both the non-ore terrain and the selected ore blocks into the
	 * offscreen depth buffer, so embedded ores don't act as see-through holes
	 * in the skeleton's occlusion surface.
	 */
	private void renderOffscreenDepth(ChunkRenderMatrices matrices, double x,
		double y, double z)
	{
		SodiumSkeletonRenderState.run(Phase.DEPTH_OFFSCREEN, () -> {
			renderSectionManager.renderLayer(matrices,
				DefaultTerrainRenderPasses.CUTOUT, x, y, z);
			renderSectionManager.renderLayer(matrices,
				DefaultTerrainRenderPasses.TRANSLUCENT, x, y, z);
		});
	}

	private void renderFallback(ChunkRenderMatrices matrices, double x,
		double y, double z)
	{
		render(Phase.DEPTH, matrices, x, y, z);
		render(Phase.LINES, matrices, x, y, z);
	}
}
