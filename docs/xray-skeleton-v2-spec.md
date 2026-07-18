# X-Ray Skeleton v2 — Post-process world-grid renderer

Target: repo `/Users/linyanyu/20-29-Development/21-Active-Projects/wurst7`, branch
`feature/xray-skeleton-sodium` (v1 committed as `a1ce13c60`). MC 1.20.1, Yarn
1.20.1+build.10, Sodium 0.5.11 (compile-only dep already configured), Aged
instance = Sodium + Indium + Iris installed/shaders off, macOS Apple Silicon
(OpenGL 4.1 core max, GL line width clamped to 1px).

## Problem with v1 (current code)

v1 renders Sodium CUTOUT terrain twice: a depth prepass into the main
framebuffer, then the same geometry in `GL_LINE` mode
(`SodiumWorldRendererMixin.renderJitteredLines`, `SodiumSkeletonRenderState`
LINES phase). Verified working in-game, but:

- Lines are 1px (macOS clamps line width); jitter workaround tops out ~4px and
  re-renders all terrain geometry per offset (up to 7 passes).
- Lines are textured (Sodium's fragment shader samples the block atlas), low
  contrast.
- Triangle diagonals appear inside quads (Sodium uses shared triangle indices).

## v2 design — replace the LINES path entirely

Keep: Style setting, support gating (Sodium 0.5.11 exact, Iris shader packs
rejected + runtime recheck), RenderLayers routing (non-ores → CUTOUT_MIPPED,
ores → TRANSLUCENT), ORES pass (translucent, depth ALWAYS), fail-safe
restores, hook-handshake (`areHooksReady`).

Replace: DEPTH-to-main-FB + jittered GL_LINE passes with:

1. **Offscreen depth prepass.** A dedicated framebuffer (`SimpleFramebuffer`
   with depth attachment, window-sized, resized on window resize, deleted on
   disable/world change). In the SOLID `drawChunkLayer` interception:
   - bind offscreen FB, clear depth (and color to transparent black),
   - render `DefaultTerrainRenderPasses.CUTOUT` once, polygon FILL, depth
     write on, color mask OFF (depth-only; keep cutout alpha discard working —
     Sodium's shader runs, we just mask color),
   - unbind back to the main framebuffer (`MinecraftClient.getFramebuffer().beginWrite(false)`).
   - Do NOT write terrain depth into the main framebuffer at all. Consequence
     (intentional): entities/block entities/chests are no longer occluded by
     invisible terrain — this FIXES v1's known chest-hiding limitation.

2. **Fullscreen grid+edge shader pass** onto the main framebuffer:
   - Raw GL program compiled at runtime from embedded GLSL 150 strings
     (LWJGL `GL20C`/`GL30C`; no MC resource pipeline, no JSON post chains —
     avoids resource-reload interactions). Compile lazily on first use; on
     compile/link failure, log once, chat-warn once, and permanently fall back
     to the v1 GL_LINE path for the session (keep v1 code as fallback).
   - Fullscreen triangle via a small VAO (3 vertices, no VBO needed if using
     gl_VertexID trick — GLSL 150 supports gl_VertexID).
   - Inputs (uniforms): offscreen depth texture (unit 0), inverse of
     (projection × modelView) matrix for the frame, camera world position
     split into integer-block part and fractional part (double precision split
     on CPU: `Vec3d cam; BlockPos camBlock = floor; vec3 camFrac`), screen
     size, line color (RGBA), line thickness px, outline thickness px.
   - Matrices: capture the exact `ChunkRenderMatrices` Sodium passes into
     `drawChunkLayer` (projection + modelView are camera-relative — Sodium
     geometry is region-relative but the modelView passed here is the world
     modelView with camera translation; verify by reading
     `SodiumWorldRenderer.drawChunkLayer` call site in
     `/tmp/sodium-fabric-0.5.11/.../mixin/core/render/world/WorldRendererMixin.java:102-110`
     — matrices come from vanilla `renderLayer`, i.e. camera-relative world
     space). Reconstruct view ray per pixel: NDC → inverse(proj*mv) →
     camera-relative world direction; worldPos = camRel + camera position.
     For grid precision far from origin compute `blockLocal = fract(camFrac + camRelPos)`
     -style arithmetic keeping everything small (never form absolute world
     floats > few thousand without splitting).
   - Depth: sample offscreen depth; if depth == 1.0 (far plane) discard (no
     terrain there).
   - Grid logic (diagonal-free block grid): compute `d = distToInt(worldPos)`
     per axis (`min(fract(p), 1-fract(p))`). A pixel is on a block edge when
     **at least 2 axes** are within threshold (one axis is ~0 across an entire
     axis-aligned face; the second crossing marks the block border).
     Threshold per axis = `linePx * fwidth(worldPos.axis)` for constant
     screen-space width. Clamp fwidth to avoid haloing at grazing angles.
   - Silhouette/outline logic: depth-discontinuity edge detect — compare
     linearized depth against neighbors at ±outlinePx (simple cross kernel);
     where discontinuity exceeds a slope-aware threshold, it's an outline
     pixel. Outline draws at full alpha; interior grid can draw slightly
     dimmer (e.g. 85% alpha) for hierarchy. Both same base color.
   - Output: solid `lineColor` with computed alpha; `discard` otherwise.
     Blend: standard alpha blend onto main FB. Depth test OFF, depth write
     OFF for this pass.
   - GL state: save/restore blend enable/func, depth test/mask, active
     texture, bound program, VAO — restore everything in finally.

3. **Order within frame** (all inside the SOLID-layer interception, before
   ci.cancel()): offscreen depth prepass → fullscreen grid pass. TRANSLUCENT
   interception unchanged (ORES phase). Ores therefore draw AFTER the grid —
   visible on top. Good.

## Settings changes (XRayHack)

- Keep `Skeleton line width` slider but rewire: 1–8 px, default 2, feeds the
  shader uniform. No more jitter table.
- Add `ColorSetting skeletonColor` ("Skeleton color", default e.g. #00FF00 or
  white — pick green #00E676-ish for visibility; Wurst has
  `net.wurstclient.settings.ColorSetting`, see ChestEspHack usage).
- Add `SliderSetting skeletonOutline` ("Skeleton outline", 0–4 px, default 2,
  0 = off) for the silhouette edge thickness.
- Getters for the mixin/util to read.

## Files

- `src/main/java/net/wurstclient/util/SkeletonPostRenderer.java` (NEW):
  framebuffer lifecycle, shader compile/cache, fullscreen draw, GL
  save/restore, `close()` on disable. No Sodium imports.
- `src/main/java/net/wurstclient/mixin/SodiumWorldRendererMixin.java`:
  replace jitter path; capture matrices; call SkeletonPostRenderer. Keep
  hook-handshake + ORES path. Keep v1 line path callable as fallback when
  shader init failed.
- `src/main/java/net/wurstclient/util/SodiumSkeletonRenderState.java`: DEPTH
  phase now also used for offscreen pass (unchanged semantics); LINES phase
  retained only for fallback.
- `src/main/java/net/wurstclient/hacks/XRayHack.java`: settings above; call
  `SkeletonPostRenderer.close()` in onDisable; keep all gating.
- `gradle.properties`: bump `mod_version` to
  `v7.50.3-tunnellerfix.1-xrayskeleton.2-MC1.20.1`.
- `ARTHUR_QOL_BACKLOG.md`: update status when done.

## Constraints & gotchas

- OpenGL 4.1 core max on macOS: GLSL `#version 150` (or 330 core — 4.1
  supports up to 410; use 330 core for textureSize/gl_VertexID comfort).
- Never leave FB bound: always return to `MinecraftClient.getInstance().getFramebuffer().beginWrite(false)`
  in finally.
- Window resize: recreate offscreen FB when dimensions differ from
  `MC.getWindow().getFramebufferWidth()/Height()`.
- `RenderSystem.assertOnRenderThread` context: all calls happen inside
  drawChunkLayer (render thread) — fine.
- Iris shaders active → gating already falls back before any of this runs.
- Do NOT touch spotless/license headers of unrelated files. Build with:
  `bash gradlew test remapJar -x spotlessCheck -x spotlessLicenseHeaderCheck -x spotlessJavaCheck -x spotlessJsonCheck`
- Follow Wurst code style (tabs, brace style as in existing files).
- Depth texture sampling: `SimpleFramebuffer` with `useDepth=true` creates a
  depth TEXTURE (`getDepthAttachment()`), sample as `sampler2D` → r channel.
- Linearize depth for edge detect using proj matrix terms, or compare raw
  depth with slope-scaled threshold — implementer's choice, but must not
  produce full-screen false edges at grazing angles.

## Acceptance criteria

1. Build passes (`test remapJar` with spotless excluded).
2. In Skeleton mode: solid-color block grid on nearest surfaces, NO texture
   on lines, NO triangle diagonals, rear surfaces produce no lines.
3. Thickness slider visibly changes width 1→8; outline slider adds stronger
   silhouette edges.
4. Ores still visible through walls; chests/entities now also visible
   (regression from v1 accepted & desired).
5. Toggling X-Ray off restores completely normal rendering; no GL leaks
   (check repeated toggles, dimension change).
6. If shader compile fails, v1 line fallback still works and a single chat
   warning appears.
