# Arthur's Wurst Bug & QoL Backlog

This document is the handoff point for Arthur's Wurst experiments and potential upstream contributions.

- Repository: `Yan-Yu-Lin/Wurst7`
- Working branch: `arthur/qol-backlog`
- Target environment: Minecraft 1.20.1, Wurst 7.50.3, Fabric
- Primary test instance: Aged modpack with Sodium 0.5.11, Indium 1.0.34, Iris 1.7.5 (shader packs currently off), and MoreCulling

Keep experimental/personal work separate from upstream-sized pull requests. Each upstream contribution should be one focused change and discussed with the Wurst maintainer before opening a PR.

## Status legend

- **Idea** — desired behavior recorded but not investigated
- **Investigated** — code paths and implementation options understood
- **Planned** — implementation approach chosen
- **In progress** — being implemented
- **Live verified** — tested successfully in Arthur's real client/server
- **Upstream candidate** — polished enough to discuss with Wurst maintainers

---

## X-Ray: Sodium-compatible surface skeleton mode

**Status:** v1 live verified; v2 implemented and build verified — v2 replaces Sodium's textured `GL_LINE` pass with an offscreen depth prepass and solid-color fullscreen grid/outline shader. It removes triangle diagonals, supports 1–8px grid thickness and 0–4px silhouettes, and no longer writes invisible terrain depth into the main framebuffer. Live verification on Aged is still required.

Known v2 live-test limitations: Indium/FRAPI custom-material terrain may bypass the CUTOUT routing used by this milestone, and the fullscreen outline pass may be expensive at Retina/4K resolution. Lowering or disabling the outline is the current performance control.

### Problem

Wurst's normal X-Ray mode completely removes non-selected blocks. Ores are easy to find, but all surrounding terrain disappears, so it is difficult to understand cave shape, distance, walls, and navigation.

Wurst has an Opacity setting intended to preserve translucent terrain, but the 1.20.1 implementation does not work with Sodium. Wurst correctly warns that Opacity is unsupported when Sodium is installed.

### Desired experience

Add a distinct X-Ray style called **Skeleton** or **Surface Skeleton**:

- Selected ores remain fully visible through walls.
- The nearest visible surface of non-ore terrain is drawn as a wireframe/skeleton.
- Blocks behind that first surface must not produce nested outlines through the front surface.
- The result should communicate terrain/cave geometry without becoming an unreadable grid of every buried block.
- This should work in Arthur's Sodium 0.5.11 Aged instance.

Conceptually:

```text
Ore blocks             -> fully visible through walls
First non-ore surface  -> visible wireframe/skeleton
Deeper non-ore blocks  -> hidden by the first surface
```

### Why existing Opacity fails with Sodium 0.5.11

The 1.20.1 Wurst path changes vanilla/Indigo vertex alpha, but Sodium builds its own chunk meshes and bypasses that renderer. Wurst can cause Sodium to select the translucent render pass, but it never changes actual pixel transparency.

Sodium 0.5's compact vertex format also uses the color alpha byte for ambient-occlusion brightness rather than framebuffer transparency. Copying the newer Wurst/Sodium alpha-mask fix into this old renderer would alter lighting instead of making blocks translucent.

Relevant existing Wurst files:

- `src/main/java/net/wurstclient/hacks/XRayHack.java`
- `src/main/java/net/wurstclient/mixin/RenderLayersMixin.java`
- `src/main/java/net/wurstclient/mixin/AbstractBlockRenderContextMixin.java`
- `src/main/java/net/wurstclient/mixin/SodiumBlockOcclusionCacheMixin.java`
- `src/main/resources/wurst.mixins.json`

Upstream history:

- Newer Wurst branches gained Sodium opacity support in commit `aef51f7f5`, followed by warning cleanup in `3dd0e733a` and later work in `4d976850e`.
- That solution targets newer Sodium rendering architecture and is not directly compatible with Minecraft 1.20.1 / Sodium 0.5.11.

### Recommended MVP: Sodium depth prepass + wireframe pass

Use Sodium's already-built chunk meshes rather than scanning blocks or raycasting every frame.

For non-ore SOLID/CUTOUT terrain:

1. Render the normal filled geometry with color writes disabled and depth writes enabled. This creates a complete depth surface for only the nearest visible terrain.
2. Render the same geometry again in polygon line mode with color enabled and depth comparison enabled.
3. The filled depth prepass hides wireframe geometry belonging to blocks behind the nearest surface.

For ore terrain:

- Route it through a separate pass that ignores terrain depth so selected ores remain visible through walls.

A plain `drawOutlinedBox(depthTest=true)` implementation is insufficient: a wireframe front block writes depth only along its edges, so lines from deeper blocks remain visible through the empty center. A full-surface depth prepass is required.

### Expected implementation areas

- `XRayHack.java`
  - Add an X-Ray style/mode setting rather than overloading the existing Opacity slider.
  - Add helpers such as `isSkeletonMode()` and ore/non-ore layer selection.
- `RenderLayersMixin.java`
  - In Skeleton mode, split selected ores from non-ore blocks/fluids instead of routing all blocks to one translucent layer.
- New optional Sodium mixin targeting `me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer`
  - Wrap Sodium terrain-layer rendering and issue depth + wireframe passes for SOLID/CUTOUT.
  - Give the ore pass through-wall depth behavior.
- New optional Sodium terrain-pass mixin
  - Reapply and restore GL state around Sodium's `startDrawing()` / `endDrawing()`, because Sodium resets render state internally.
- Small render-phase state bridge, likely `ThreadLocal`, so the terrain-pass mixin knows whether it is rendering depth, skeleton lines, or ores.
- `wurst.mixins.json`
  - Register Sodium-optional mixins safely using existing compatibility conventions.

### MVP limitations to accept initially

- Sodium's terrain is triangle-indexed. `GL_LINE` may show a diagonal inside each quad, producing a functional but imperfect skeleton.
- Textured block entities such as chests and spawners may be hidden by the terrain depth pass unless given separate through-wall rendering.
- Iris installed with shader packs disabled is the initial supported target.
- Active Iris shader packs may conflict with framebuffer/depth assumptions; first version may explicitly mark them unsupported while ensuring they do not crash.
- Rendering SOLID/CUTOUT geometry twice increases GPU terrain work. Add a Skeleton render-distance setting if needed.

### Rejected or deferred alternatives

#### Backport translucent Sodium opacity

Possible by encoding opacity into unused material bits and modifying Sodium shaders, but it still does not provide the desired first-surface skeleton. It also creates Iris shader compatibility risk.

#### CPU block scanning and outlined boxes

Easy to prototype but not suitable for exact coverage. Low ray counts miss surfaces; high ray counts are expensive at render distance 20; outlined boxes still reveal rear geometry around their empty centers.

#### Offscreen depth/normal edge detection

Best visual quality and avoids triangle diagonals, but requires a custom framebuffer and fullscreen edge shader integrated with Sodium and Iris. Reserve this for a later version if the polygon-wireframe MVP is visually unacceptable.

### Implementation checklist

- [ ] Add `Skeleton` X-Ray style setting.
- [ ] Preserve current Normal X-Ray behavior.
- [ ] Preserve current non-Sodium Opacity behavior.
- [ ] Split ore and non-ore Sodium terrain passes.
- [ ] Implement filled depth prepass for non-ore terrain.
- [ ] Implement depth-tested polygon wireframe pass.
- [ ] Render selected ores through the terrain depth surface.
- [ ] Restore every modified GL state reliably after each pass.
- [ ] Handle mode toggle, dimension change, resource reload, and chunk rebuild.
- [ ] Decide how block entities should appear.
- [ ] Add optional skeleton render-distance control if GPU cost is high.
- [ ] Document Iris shader-pack limitations.

### Verification checklist

Create a controlled scene containing:

- Three-deep stone wall
- Ore behind the wall
- A second wall/cave surface behind the first
- Stairs, slabs, glass, water, and a chest
- Chunk boundary crossing the test scene

Verify:

- [ ] Front surface skeleton is visible.
- [ ] Rear non-ore outlines are fully hidden.
- [ ] Ores remain visible through walls.
- [ ] Moving around a corner reveals the newly visible surface correctly.
- [ ] `Only exposed` continues to work.
- [ ] Breaking/placing blocks rebuilds the correct chunk geometry.
- [ ] Toggling X-Ray does not leave the whole game in line mode, color-mask-off, or depth-always state.
- [ ] Dimension changes and resource reloads are safe.
- [ ] Sodium + Indium + MoreCulling + ImmediatelyFast combination works.
- [ ] Iris installed with shaders disabled works.
- [ ] Iris shader pack enabled either works or fails gracefully without crashing.
- [ ] Measure average and 1% low frame times at render distance 20 for X-Ray off, Normal X-Ray, and Skeleton.

### Upstream contribution strategy

This mode is larger and more renderer-specific than a typical small bug fix. Build and validate it in Arthur's fork first. If it becomes stable:

1. Contact the Wurst maintainer before opening a PR.
2. Explain that this is a new Sodium-compatible X-Ray style, not a replacement for Opacity.
3. Ask whether support for the legacy 1.20.1 / Sodium 0.5 branch is wanted upstream.
4. Keep any PR focused on this single rendering feature.

---

## Future ideas

Add Arthur's next bugs and QoL requests below. Each item should include:

- Problem and reproduction
- Desired behavior
- Minecraft/Wurst versions
- Investigation findings
- Recommended implementation
- Compatibility risks
- Verification checklist
- Whether it belongs in Arthur's fork or is an upstream candidate
