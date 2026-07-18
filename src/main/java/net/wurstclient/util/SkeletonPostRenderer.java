/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.world.ClientWorld;

public enum SkeletonPostRenderer
{
	;

	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst X-Ray Skeleton");

	private static final String VERTEX_SHADER = """
		#version 330 core

		out vec2 v_Uv;

		void main()
		{
			vec2 position = vec2((gl_VertexID << 1) & 2,
				gl_VertexID & 2);
			v_Uv = position;
			gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
		}
		""";

	private static final String FRAGMENT_SHADER = """
		#version 330 core

		in vec2 v_Uv;
		out vec4 fragColor;

		uniform sampler2D u_DepthTexture;
		uniform mat4 u_InverseViewProjection;
		uniform vec4 u_ProjectionDepth;
		uniform ivec3 u_CameraBlock;
		uniform vec3 u_CameraFraction;
		uniform vec2 u_ScreenSize;
		uniform vec4 u_LineColor;
		uniform float u_LineThickness;
		uniform float u_OutlineThickness;

		vec3 reconstructPosition(vec2 uv, float depth)
		{
			vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0,
				1.0);
			vec4 position = u_InverseViewProjection * clip;
			return position.xyz / position.w;
		}

		float linearizeDepth(float depth)
		{
			float ndc = depth * 2.0 - 1.0;
			return abs((u_ProjectionDepth.y - ndc * u_ProjectionDepth.w)
				/ (ndc * u_ProjectionDepth.z - u_ProjectionDepth.x));
		}

		float sampleLinearDepth(vec2 uv)
		{
			float depth = texture(u_DepthTexture, uv).r;
			if(depth >= 0.999999)
				return -1.0;

			return linearizeDepth(depth);
		}

		void main()
		{
			float depth = texture(u_DepthTexture, v_Uv).r;
			if(depth >= 0.999999)
				discard;

			vec3 cameraRelative = reconstructPosition(v_Uv, depth);

			// Integer translation does not change the grid. Keeping only a small
			// modulo of the camera block preserves precision far from the origin.
			ivec3 localCameraBlock = u_CameraBlock % 256;
			vec3 localWorld = vec3(localCameraBlock) + u_CameraFraction
				+ cameraRelative;
			vec3 distanceToInteger = abs(fract(localWorld + 0.5) - 0.5);

			vec3 footprint = max(fwidth(localWorld), vec3(0.00002));
			footprint = min(footprint,
				vec3(0.3 / max(u_LineThickness, 1.0)));
			vec3 threshold = min(vec3(0.3),
				vec3(0.5 * u_LineThickness) * footprint);
			vec3 antialias = min(footprint * 0.75, vec3(0.08));
			vec3 axisGrid = vec3(1.0) - smoothstep(
				max(vec3(0.0), threshold - antialias),
				threshold + antialias, distanceToInteger);

			// At least two integer-boundary axes means a block edge. A single
			// boundary axis covers an entire face and is intentionally ignored.
			float grid = max(min(axisGrid.x, axisGrid.y),
				max(min(axisGrid.x, axisGrid.z),
					min(axisGrid.y, axisGrid.z)));

			float outline = 0.0;
			if(u_OutlineThickness > 0.0)
			{
				vec2 offset = vec2(u_OutlineThickness) / u_ScreenSize;
				float centerDepth = linearizeDepth(depth);
				float left =
					sampleLinearDepth(v_Uv - vec2(offset.x, 0.0));
				float right =
					sampleLinearDepth(v_Uv + vec2(offset.x, 0.0));
				float down =
					sampleLinearDepth(v_Uv - vec2(0.0, offset.y));
				float up =
					sampleLinearDepth(v_Uv + vec2(0.0, offset.y));

				if(left < 0.0 || right < 0.0 || down < 0.0 || up < 0.0)
					outline = 1.0;
				else
				{
					float discontinuity = max(
						max(abs(left - centerDepth),
							abs(right - centerDepth)),
						max(abs(down - centerDepth),
							abs(up - centerDepth)));
					float slope = max(abs(dFdx(centerDepth)),
						abs(dFdy(centerDepth)));
					float edgeThreshold = max(0.06,
						slope * (u_OutlineThickness + 1.5) * 1.5
							+ centerDepth * 0.00075);
					outline = smoothstep(edgeThreshold,
						edgeThreshold * 1.5, discontinuity);
				}
			}

			float alpha = max(grid * 0.85, outline) * u_LineColor.a;
			if(alpha <= 0.001)
				discard;

			fragColor = vec4(u_LineColor.rgb, alpha);
		}
		""";

	private static SimpleFramebuffer framebuffer;
	private static ClientWorld framebufferWorld;
	private static int program;
	private static int vertexArray;
	private static boolean postProcessFailed;
	private static boolean warningShown;

	private static int depthTextureUniform;
	private static int inverseViewProjectionUniform;
	private static int projectionDepthUniform;
	private static int cameraBlockUniform;
	private static int cameraFractionUniform;
	private static int screenSizeUniform;
	private static int lineColorUniform;
	private static int lineThicknessUniform;
	private static int outlineThicknessUniform;

	public static boolean render(Matrix4fc projection, Matrix4fc modelView,
		int cameraBlockX, int cameraBlockY, int cameraBlockZ,
		float cameraFractionX, float cameraFractionY, float cameraFractionZ,
		Runnable depthPass, Color lineColor, float lineThickness,
		float outlineThickness)
	{
		RenderSystem.assertOnRenderThread();
		if(postProcessFailed)
			return false;

		MinecraftClient mc = MinecraftClient.getInstance();
		FramebufferState framebufferState = null;
		Throwable failure = null;
		try
		{
			framebufferState = new FramebufferState();
			ensureShader();
			ensureFramebuffer(mc);
			framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
			framebuffer.beginWrite(false);
			depthPass.run();
			mc.getFramebuffer().beginWrite(false);
			drawGrid(projection, modelView, cameraBlockX, cameraBlockY,
				cameraBlockZ, cameraFractionX, cameraFractionY, cameraFractionZ,
				lineColor, lineThickness, outlineThickness);

		}catch(RuntimeException | LinkageError | OutOfMemoryError e)
		{
			failure = e;
		}

		failure = restoreFramebufferState(mc, framebufferState, failure);
		if(failure == null)
			return true;

		disablePostProcess(failure);
		restoreFramebufferState(mc, framebufferState, null);
		return false;
	}

	private static void ensureFramebuffer(MinecraftClient mc)
	{
		int width = Math.max(1, mc.getWindow().getFramebufferWidth());
		int height = Math.max(1, mc.getWindow().getFramebufferHeight());
		if(framebuffer != null && framebufferWorld == mc.world
			&& framebuffer.textureWidth == width
			&& framebuffer.textureHeight == height)
			return;

		deleteFramebuffer();
		framebuffer = new SimpleFramebuffer(width, height, true,
			MinecraftClient.IS_SYSTEM_MAC);
		framebuffer.setClearColor(0, 0, 0, 0);
		framebufferWorld = mc.world;
	}

	private static Throwable restoreFramebufferState(MinecraftClient mc,
		FramebufferState state, Throwable failure)
	{
		try
		{
			mc.getFramebuffer().beginWrite(false);

		}catch(RuntimeException | LinkageError | OutOfMemoryError e)
		{
			failure = appendFailure(failure, e);
		}

		if(state != null)
			try
			{
				state.restore();

			}catch(RuntimeException | LinkageError | OutOfMemoryError e)
			{
				failure = appendFailure(failure, e);
			}

		return failure;
	}

	private static Throwable appendFailure(Throwable failure,
		Throwable additionalFailure)
	{
		if(failure == null)
			return additionalFailure;

		failure.addSuppressed(additionalFailure);
		return failure;
	}

	private static void disablePostProcess(Throwable failure)
	{
		postProcessFailed = true;
		LOGGER.error("Skeleton X-Ray post-processing failed", failure);
		try
		{
			deleteFramebuffer();

		}catch(RuntimeException | LinkageError | OutOfMemoryError cleanupFailure)
		{
			failure.addSuppressed(cleanupFailure);
			LOGGER.error("Could not delete the Skeleton X-Ray framebuffer",
				cleanupFailure);
		}

		try
		{
			deleteShader();

		}catch(RuntimeException | LinkageError | OutOfMemoryError cleanupFailure)
		{
			failure.addSuppressed(cleanupFailure);
			LOGGER.error("Could not delete the Skeleton X-Ray shader",
				cleanupFailure);
		}

		if(warningShown)
			return;

		warningShown = true;
		try
		{
			ChatUtils.warning("Skeleton X-Ray post-processing failed. Using the"
				+ " 1px wireframe fallback for this session. See the log for"
				+ " details.");

		}catch(RuntimeException | OutOfMemoryError warningFailure)
		{
			LOGGER.error("Could not show the Skeleton X-Ray fallback warning",
				warningFailure);
		}
	}

	private static void ensureShader()
	{
		if(program != 0)
			return;

		int vertexShader = compileShader(GL20C.GL_VERTEX_SHADER, VERTEX_SHADER);
		int fragmentShader = 0;
		try
		{
			fragmentShader =
				compileShader(GL20C.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
			program = GL20C.glCreateProgram();
			if(program == 0)
				throw new IllegalStateException("Could not create shader program");
			GL20C.glAttachShader(program, vertexShader);
			GL20C.glAttachShader(program, fragmentShader);
			GL20C.glLinkProgram(program);
			if(GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS)
				== GL11C.GL_FALSE)
				throw new IllegalStateException("Shader link failed: "
					+ GL20C.glGetProgramInfoLog(program));

		}finally
		{
			GL20C.glDeleteShader(vertexShader);
			if(fragmentShader != 0)
				GL20C.glDeleteShader(fragmentShader);
		}

		vertexArray = GL30C.glGenVertexArrays();
		if(vertexArray == 0)
			throw new IllegalStateException("Could not create fullscreen VAO");
		depthTextureUniform =
			GL20C.glGetUniformLocation(program, "u_DepthTexture");
		inverseViewProjectionUniform = GL20C.glGetUniformLocation(program,
			"u_InverseViewProjection");
		projectionDepthUniform =
			GL20C.glGetUniformLocation(program, "u_ProjectionDepth");
		cameraBlockUniform =
			GL20C.glGetUniformLocation(program, "u_CameraBlock");
		cameraFractionUniform =
			GL20C.glGetUniformLocation(program, "u_CameraFraction");
		screenSizeUniform =
			GL20C.glGetUniformLocation(program, "u_ScreenSize");
		lineColorUniform =
			GL20C.glGetUniformLocation(program, "u_LineColor");
		lineThicknessUniform =
			GL20C.glGetUniformLocation(program, "u_LineThickness");
		outlineThicknessUniform =
			GL20C.glGetUniformLocation(program, "u_OutlineThickness");
	}

	private static int compileShader(int type, String source)
	{
		int shader = GL20C.glCreateShader(type);
		if(shader == 0)
			throw new IllegalStateException("Could not create shader");
		GL20C.glShaderSource(shader, source);
		GL20C.glCompileShader(shader);
		if(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS)
			!= GL11C.GL_FALSE)
			return shader;

		String log = GL20C.glGetShaderInfoLog(shader);
		GL20C.glDeleteShader(shader);
		throw new IllegalStateException("Shader compile failed: " + log);
	}

	private static void drawGrid(Matrix4fc projection, Matrix4fc modelView,
		int cameraBlockX, int cameraBlockY, int cameraBlockZ,
		float cameraFractionX, float cameraFractionY, float cameraFractionZ,
		Color lineColor, float lineThickness, float outlineThickness)
	{
		GlState state = new GlState();
		try
		{
			GL20C.glUseProgram(program);
			GL30C.glBindVertexArray(vertexArray);

			GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
			GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
				framebuffer.getDepthAttachment());
			GL20C.glUniform1i(depthTextureUniform, 0);

			Matrix4f inverseViewProjection =
				new Matrix4f(projection).mul(modelView).invert();
			try(MemoryStack stack = MemoryStack.stackPush())
			{
				FloatBuffer matrix = stack.mallocFloat(16);
				inverseViewProjection.get(matrix);
				GL20C.glUniformMatrix4fv(inverseViewProjectionUniform, false,
					matrix);
			}
			GL20C.glUniform4f(projectionDepthUniform, projection.m22(),
				projection.m32(), projection.m23(), projection.m33());

			GL20C.glUniform3i(cameraBlockUniform, cameraBlockX, cameraBlockY,
				cameraBlockZ);
			GL20C.glUniform3f(cameraFractionUniform, cameraFractionX,
				cameraFractionY, cameraFractionZ);

			GL20C.glUniform2f(screenSizeUniform, framebuffer.textureWidth,
				framebuffer.textureHeight);
			GL20C.glUniform4f(lineColorUniform, lineColor.getRed() / 255F,
				lineColor.getGreen() / 255F, lineColor.getBlue() / 255F,
				lineColor.getAlpha() / 255F);
			GL20C.glUniform1f(lineThicknessUniform, lineThickness);
			GL20C.glUniform1f(outlineThicknessUniform, outlineThickness);

			GL11C.glEnable(GL11C.GL_BLEND);
			GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA,
				GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE,
				GL11C.GL_ONE_MINUS_SRC_ALPHA);
			GL11C.glDisable(GL11C.GL_DEPTH_TEST);
			GL11C.glDepthMask(false);
			GL11C.glDisable(GL11C.GL_CULL_FACE);
			GL11C.glColorMask(true, true, true, true);
			GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

		}finally
		{
			state.restore();
		}
	}

	public static void closeFramebuffer()
	{
		if(!RenderSystem.isOnRenderThread())
		{
			RenderSystem.recordRenderCall(
				SkeletonPostRenderer::closeFramebufferOnRenderThread);
			return;
		}

		closeFramebufferOnRenderThread();
	}

	private static void closeFramebufferOnRenderThread()
	{
		try
		{
			deleteFramebuffer();

		}catch(RuntimeException | LinkageError | OutOfMemoryError e)
		{
			LOGGER.error("Could not delete the Skeleton X-Ray framebuffer", e);
		}
	}

	public static void close()
	{
		if(!RenderSystem.isOnRenderThread())
		{
			RenderSystem.recordRenderCall(SkeletonPostRenderer::closeOnRenderThread);
			return;
		}

		closeOnRenderThread();
	}

	private static void closeOnRenderThread()
	{
		closeFramebufferOnRenderThread();
		try
		{
			deleteShader();

		}catch(RuntimeException | LinkageError | OutOfMemoryError e)
		{
			LOGGER.error("Could not delete the Skeleton X-Ray shader", e);
		}
	}

	private static void deleteFramebuffer()
	{
		SimpleFramebuffer oldFramebuffer = framebuffer;
		framebuffer = null;
		framebufferWorld = null;
		if(oldFramebuffer != null)
			oldFramebuffer.delete();
	}

	private static void deleteShader()
	{
		int oldProgram = program;
		int oldVertexArray = vertexArray;
		program = 0;
		vertexArray = 0;
		if(oldProgram != 0)
			GL20C.glDeleteProgram(oldProgram);
		if(oldVertexArray != 0)
			GL30C.glDeleteVertexArrays(oldVertexArray);
	}

	private static final class FramebufferState
	{
		private final int drawFramebuffer =
			GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
		private final int readFramebuffer =
			GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
		private final int activeTexture =
			GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
		private final int activeTextureBinding =
			GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
		private final int texture0Binding;
		private final int viewportX;
		private final int viewportY;
		private final int viewportWidth;
		private final int viewportHeight;
		private final float clearRed;
		private final float clearGreen;
		private final float clearBlue;
		private final float clearAlpha;

		private FramebufferState()
		{
			if(activeTexture == GL13C.GL_TEXTURE0)
				texture0Binding = activeTextureBinding;
			else
			{
				RenderSystem.activeTexture(GL13C.GL_TEXTURE0);
				texture0Binding =
					GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
				RenderSystem.activeTexture(activeTexture);
			}

			try(MemoryStack stack = MemoryStack.stackPush())
			{
				IntBuffer viewport = stack.mallocInt(4);
				GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
				viewportX = viewport.get(0);
				viewportY = viewport.get(1);
				viewportWidth = viewport.get(2);
				viewportHeight = viewport.get(3);

				FloatBuffer clearColor = stack.mallocFloat(4);
				GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, clearColor);
				clearRed = clearColor.get(0);
				clearGreen = clearColor.get(1);
				clearBlue = clearColor.get(2);
				clearAlpha = clearColor.get(3);
			}
		}

		private void restore()
		{
			GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,
				drawFramebuffer);
			GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,
				readFramebuffer);
			RenderSystem.viewport(viewportX, viewportY, viewportWidth,
				viewportHeight);
			GlStateManager._clearColor(clearRed, clearGreen, clearBlue,
				clearAlpha);

			RenderSystem.activeTexture(GL13C.GL_TEXTURE0);
			GlStateManager._bindTexture(texture0Binding);
			if(activeTexture != GL13C.GL_TEXTURE0)
			{
				RenderSystem.activeTexture(activeTexture);
				GlStateManager._bindTexture(activeTextureBinding);
			}
		}
	}

	private static final class GlState
	{
		private final boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
		private final boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
		private final boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
		private final boolean depthMask =
			GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
		private final int blendSourceRgb =
			GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
		private final int blendDestinationRgb =
			GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
		private final int blendSourceAlpha =
			GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
		private final int blendDestinationAlpha =
			GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
		private final int activeTexture =
			GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
		private final int program =
			GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		private final int vertexArray =
			GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
		private final int texture;
		private final boolean colorRed;
		private final boolean colorGreen;
		private final boolean colorBlue;
		private final boolean colorAlpha;

		private GlState()
		{
			GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
			texture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
			GL13C.glActiveTexture(activeTexture);

			try(MemoryStack stack = MemoryStack.stackPush())
			{
				ByteBuffer colorMask = stack.malloc(4);
				GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMask);
				colorRed = colorMask.get(0) != 0;
				colorGreen = colorMask.get(1) != 0;
				colorBlue = colorMask.get(2) != 0;
				colorAlpha = colorMask.get(3) != 0;
			}
		}

		private void restore()
		{
			setEnabled(GL11C.GL_BLEND, blend);
			GL14C.glBlendFuncSeparate(blendSourceRgb, blendDestinationRgb,
				blendSourceAlpha, blendDestinationAlpha);
			setEnabled(GL11C.GL_DEPTH_TEST, depthTest);
			GL11C.glDepthMask(depthMask);
			setEnabled(GL11C.GL_CULL_FACE, cull);
			GL11C.glColorMask(colorRed, colorGreen, colorBlue, colorAlpha);

			GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
			GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
			GL13C.glActiveTexture(activeTexture);
			GL20C.glUseProgram(program);
			GL30C.glBindVertexArray(vertexArray);
		}

		private static void setEnabled(int capability, boolean enabled)
		{
			if(enabled)
				GL11C.glEnable(capability);
			else
				GL11C.glDisable(capability);
		}
	}
}
