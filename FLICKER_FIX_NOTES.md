# FSR2+ flicker fix (26.1.2) — what changed and how to restore full quality

## The crash you just hit
`mixin apply failed: Argument modifier method fsr$jitterWorldProjection ... Scanned 0 target(s)`.

The `@ModifyArg` targeted `Lhot;->a(...)`. `hot`/`a` are the **obfuscated** names of
`RawProjectionMatrix.set` (confirmed via the Yarn javadoc mappings table). But this
project compiles against **Mojmap**, and at runtime the Minecraft classes are in the
Mojmap (named) namespace — so the bytecode references the *Mojmap* name of that
class/method, not `hot`/`a`. Mixin scanned `renderLevel` for an invoke of `hot.a`,
found none, and aborted the whole transform → crash at startup.

## The flicker root cause (why FSR2+ flickered in-world only)
The temporal pipeline de-jitters every frame by the Halton jitter
(`MV_GEN_CS`: `ndc = uv*2-1 + 2*uJitter/renderSize`, accumulate: `lrUv = hrUv - uJitter/renderSize`).
That is only correct if the world was actually rendered WITH that jitter in the
projection matrix. In 26.1.2 the projection jitter hook never worked, so the world
rendered **unjittered** while the pipeline still de-jittered → the image was shifted
by a different sub-pixel Halton amount every frame → shimmer. FSR1 has no
jitter/de-jitter, so it never flickered; and it only happens in-world because the
projection is only set for the level render.

## The fix applied now (stable, no fragile names)
1. `GameRendererMixin`: removed the broken `@ModifyArg` (and the unused
   `@Shadow gameRenderState`). Kept the proven `render` HEAD/TAIL and `renderLevel`
   TAIL injects. The world renders unjittered.
2. `FSRProcessor.onRenderBegin`: forces `jitterX = jitterY = 0` for temporal modes,
   so the pipeline's de-jitter matches the unjittered render. **This stops the flicker.**
   Temporal accumulation still works via motion vectors; only the sub-pixel Halton
   reconstruction is lost (slightly less fine detail than a fully-jittered setup).
3. `FSRProcessor.updateProjectionFromCamera()`: rebuilds `lastProj` from the camera
   each frame (base FOV from options + window aspect + near 0.05 + render-distance
   far). This replaces the projection the removed hook used to capture, so camera
   params (near/far/fov) and the motion-vector matrices stay valid. Wrapped in
   try/catch so it can never crash the render thread.

## Restoring FULL quality (optional — real sub-pixel jitter)
You need the **Mojmap** name of `RawProjectionMatrix.set`. Get it from your mapped jar:

```bat
javap -p -c -classpath "C:\Users\arshjot\Downloads\fsr2mod\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-clientOnly-29dc61e9cc\26.1.2\minecraft-clientOnly-29dc61e9cc-26.1.2.jar" net.minecraft.client.renderer.GameRenderer > gr.txt
```

Open `gr.txt`, find the `renderLevel` method, and look for the `invokevirtual` whose
descriptor is `(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;`.
It will look like:

```
invokevirtual <OWNER>.<METHOD>:(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;
```

`<OWNER>` is the Mojmap name of RawProjectionMatrix and `<METHOD>` is the Mojmap name
of set. Then re-add this to `GameRendererMixin` (and un-zero the jitter in
`FSRProcessor.onRenderBegin`):

```java
@ModifyArg(
    method = "renderLevel",
    at = @At(value = "INVOKE",
        target = "L<OWNER>;-><METHOD>(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
        ordinal = 0),
    index = 0
)
private Matrix4f fsr$jitterWorldProjection(Matrix4f original) {
    FSRProcessor processor = FSRMod.getInstance().getProcessor();
    return processor == null ? original : processor.captureAndJitter(original);
}
```

(`captureAndJitter` is still present in FSRProcessor for exactly this.) Paste the
`<OWNER>`/`<METHOD>` you found and tell me — or just drop them in yourself.
