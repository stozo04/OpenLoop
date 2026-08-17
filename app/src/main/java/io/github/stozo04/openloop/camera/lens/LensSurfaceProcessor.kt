package io.github.stozo04.openloop.camera.lens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.content.res.ResourcesCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.sin

/**
 * The lens renderer: a CameraX [SurfaceProcessor] that draws every camera frame, composites the
 * active lens's stickers and features over it, and hands the result to *all* of the effect's
 * outputs — preview and video recorder alike.
 *
 * ## Why one processor for every lens
 *
 * CameraX allows at most one effect per target, and an effect can only be attached or detached at
 * `bindToLifecycle`. A rebind mid-recording tears the camera out from under the in-flight capture
 * and finalizes it with `ERROR_SOURCE_INACTIVE` — the failure this repo already learned twice
 * (Lesson 012, Issue #36). So this processor is attached on *every* bind and stays attached for
 * the life of the binding; picking a lens is a [setLens] field write, never a rebind. With no lens
 * the shader is an identity pass-through.
 *
 * ## Threading
 *
 * All GL work happens on [glThread]. [setLens] is called from the main thread and [setFace] from
 * the ML Kit analyzer thread; both are `@Volatile` reads on the GL side — a lens or face that lands
 * one frame late is invisible, and a lock here would stall the camera.
 */
class LensSurfaceProcessor(context: Context) : SurfaceProcessor {

    private val appContext = context.applicationContext

    private val glThread = HandlerThread("OpenLoopLensGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    /** Executor CameraX uses for its callbacks — same thread as GL, so no extra hop. */
    val glExecutor: Executor = Executor { glHandler.post(it) }

    @Volatile
    private var activeLens: Lens? = null

    @Volatile
    private var face: FaceSnapshot? = null

    @Volatile
    private var released: Boolean = false

    // ---- EGL / GL state. Touched only on glThread. ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var currentInput: InputSurfaceState? = null

    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    /** Outputs whose geometry has already been logged — one line per surface, not per frame. */
    private val loggedOutputs = HashSet<SurfaceOutput>()

    private var cameraProgram = 0
    private var stickerProgram = 0
    private var featureProgram = 0

    /**
     * Uploaded art, keyed by **drawable** rather than by lens. A lens can now carry several layers,
     * and two of them can be the same file — Twisted Tongue draws one eyeball on each eye — so
     * keying by lens would either upload the same bitmap twice or need a nested map.
     */
    private val stickerTextures = HashMap<Int, Int>()

    // ---- Wobble state. Touched only on glThread, stepped once per frame (see [stepWobbles]). ----

    /** The lens the states below belong to; a change clears them so a swing cannot carry over. */
    private var wobbleLens: Lens? = null
    private val wobbleStates = HashMap<Int, LensPhysics.Wobble>()

    /** This frame's swing angle per art layer, index-aligned with the lens's art. Reused. */
    private var wobbleAngles = FloatArray(0)

    /** Previous frame's face and timestamp — the two things a spring step needs. */
    private var previousFace: FaceSnapshot? = null
    private var previousFrameNs = 0L

    /**
     * Eased mouth openness, `0f`..`1f`. The detector's raw value jitters frame to frame; easing it
     * is what turns a twitchy number into an animation. Held per-processor rather than per-layer
     * because it describes the *subject*, not a lens.
     */
    private var easedOpenness = 0f

    private val surfaceTextureMatrix = FloatArray(MATRIX_SIZE)
    private val outputTextureMatrix = FloatArray(MATRIX_SIZE)

    private val fullscreenPositions = floatBufferOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    )

    /**
     * The unit square, in strip order — used by the fullscreen camera pass and by the sticker,
     * whose corners follow the same order ([CORNER_SIGNS]: top-left, top-right, bottom-left,
     * bottom-right in screen terms).
     *
     * `GLUtils.texImage2D` uploads a bitmap's FIRST row at `v = 0`, so `v = 0` is the top of the
     * art. Pairing the screen's top corners with `v = 1` — the natural-looking choice, and the
     * first thing written here — renders every sticker upside down. It is easy to miss on
     * near-symmetric art like the shades and obvious the moment a broccoli's stalk appears above
     * the head.
     */
    private val quadTexCoords = floatBufferOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
    )

    /** Corners are written straight into these each frame — see [writeStickerCorners]. */
    private val stickerPositionBuffer = floatBufferOf(*FloatArray(QUAD_COMPONENTS))
    private val featurePositionBuffer = floatBufferOf(*FloatArray(QUAD_COMPONENTS))

    /** Local coordinates over a feature quad, `-1..1`, used for the elliptical fade and sampling. */
    private val featureLocalBuffer = floatBufferOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    )

    // ------------------------------------------------------------------ public API

    /** Selects the active lens, or `null` for a clean pass-through. Safe to call while recording. */
    fun setLens(lens: Lens?) {
        activeLens = lens
    }

    /** Publishes the newest tracked face, or `null` when nobody is in frame. */
    fun setFace(snapshot: FaceSnapshot?) {
        face = snapshot
    }

    /** Tears down the GL thread. The processor is unusable afterwards. */
    fun release() {
        released = true
        glHandler.post {
            releaseGl()
            glThread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ SurfaceProcessor

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        glHandler.post {
            if (!ensureEgl()) {
                request.willNotProvideSurface()
                return@post
            }

            val textureId = createExternalTexture()
            val surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
                setOnFrameAvailableListener({ drawFrame() }, glHandler)
            }
            val state = InputSurfaceState(textureId, surfaceTexture, Surface(surfaceTexture))

            // Deliberately do NOT tear down the previous input here. CameraX owns the surface it
            // was handed until it fires that request's result callback, and releasing early — then
            // releasing again from the callback — is a double free on the SurfaceTexture.
            currentInput = state

            request.provideSurface(state.surface, glExecutor) {
                // CameraX is done with this surface (camera restart, unbind, or an error).
                state.release()
                if (currentInput === state) currentInput = null
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) {
            surfaceOutput.close()
            return
        }
        glHandler.post {
            if (!ensureEgl()) {
                surfaceOutput.close()
                return@post
            }
            val surface = surfaceOutput.getSurface(glExecutor) {
                // The only event today is REQUEST_CLOSE; treat anything else the same way.
                outputs.remove(surfaceOutput)?.let { EGL14.eglDestroySurface(eglDisplay, it) }
                loggedOutputs.remove(surfaceOutput)
                surfaceOutput.close()
            }
            val eglSurface = createWindowSurface(surface)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Could not create an EGL surface for output ${surfaceOutput.targets}")
                surfaceOutput.close()
                return@post
            }
            outputs[surfaceOutput] = eglSurface
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun drawFrame() {
        val input = currentInput?.takeIf { !it.released } ?: return
        val surfaceTexture = input.surfaceTexture

        // Always consume the frame first, even with nowhere to draw it — skipping updateTexImage()
        // stalls the producer once the buffer queue fills, which shows up as a frozen viewfinder.
        makeCurrentPbuffer()
        surfaceTexture.updateTexImage()
        if (outputs.isEmpty()) return

        surfaceTexture.getTransformMatrix(surfaceTextureMatrix)
        val timestampNs = surfaceTexture.timestamp

        val lens = activeLens
        val trackedFace = face

        // Physics advances ONCE per frame, here, before the per-output loop. Stepping it inside the
        // loop would run the simulation once per output — the preview and the recording would each
        // see a differently-advanced spring, and with two outputs attached the tongue would swing at
        // double speed. The angles come out dimensionless, so every output can use them as-is.
        val wobbles = stepWobbles(lens, trackedFace, timestampNs)
        val openFraction = easedOpenness

        for ((output, eglSurface) in outputs) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) continue

            val size = output.size
            val frameAspect = size.width.toFloat() / size.height.toFloat()
            output.updateTransformMatrix(outputTextureMatrix, surfaceTextureMatrix)
            logOutputGeometryOnce(output, size)

            // The tracker read a different stream off the same sensor; re-frame its landmarks onto
            // this output's shape, then rebuild the face frame from them. Orientation and scale
            // come out of that frame, so nothing here needs a mirror flag or a roll angle.
            val snapshot = trackedFace?.let { LensAnchor.reframe(it, frameAspect) }
            val faceFrame = snapshot?.let { LensAnchor.faceFrame(it, frameAspect) }

            GLES20.glViewport(0, 0, size.width, size.height)
            drawCamera(input.textureId)
            // Layers paint in catalogue order, so a lens controls its own stacking — Twisted
            // Tongue's teeth land on top of its tongue purely by sitting later in the list.
            if (lens != null && snapshot != null && faceFrame != null) {
                lens.art.forEachIndexed { index, art ->
                    drawSticker(
                        art = art,
                        face = snapshot,
                        faceFrame = faceFrame,
                        frameAspect = frameAspect,
                        wobbleRadians = wobbles.getOrElse(index) { 0f },
                        openFraction = openFraction,
                    )
                }
            }
            // Character lenses paste the subject's own eyes and mouth back on top of the art, so
            // the vegetable does the acting. Must come after the sticker, which is opaque.
            val layout = lens?.features
            if (layout != null && snapshot != null && faceFrame != null) {
                LensAnchor.features(snapshot, faceFrame, layout, frameAspect).forEach { feature ->
                    drawFeature(input.textureId, feature, frameAspect)
                }
            }

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun drawCamera(cameraTextureId: Int) {
        GLES20.glUseProgram(cameraProgram)
        GLES20.glDisable(GLES20.GL_BLEND)

        bindAttribute(cameraProgram, "aPosition", fullscreenPositions)
        bindAttribute(cameraProgram, "aTexCoord", quadTexCoords)

        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix"),
            1,
            false,
            outputTextureMatrix,
            0,
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "uCamera"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
    }

    private fun drawSticker(
        art: LensArt,
        face: FaceSnapshot,
        faceFrame: FaceFrame,
        frameAspect: Float,
        wobbleRadians: Float,
        openFraction: Float,
    ) {
        val texture = stickerTextures.getOrPut(art.drawableRes) { loadTexture(art.drawableRes) }
        if (texture == 0) return

        val quad = LensAnchor.sticker(
            face, faceFrame, art.placement, frameAspect, wobbleRadians, openFraction,
        )
        // A fully-retracted layer has no pixels; skip the draw call entirely.
        if (quad.halfWidth <= 0f || quad.halfHeight <= 0f) return
        writeStickerCorners(quad, frameAspect)

        GLES20.glUseProgram(stickerProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Android bitmaps arrive premultiplied, so source alpha is already folded into RGB.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        bindAttribute(stickerProgram, "aPosition", stickerPositionBuffer)
        bindAttribute(stickerProgram, "aTexCoord", quadTexCoords)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(stickerProgram, "uSticker"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Advances every wobbling layer of [lens] by one frame and returns their swing angles.
     *
     * Three things make this correct, and each was a bug waiting to happen:
     *
     * 1. **Once per frame, not once per output.** Called from [drawFrame] *before* the output loop.
     * 2. **In the tracker's own space.** The drive is measured on the raw snapshot against its own
     *    `sourceAspect`, never an output's shape. [LensAnchor.lateralShiftInUnits] divides by the
     *    face unit, so the result is dimensionless and means the same thing to preview and recorder.
     * 3. **No face means settle, not snap.** With no previous face the shift is zero, which decays
     *    the spring toward rest instead of teleporting it — matching [FaceTracker]'s own drop-out
     *    hold, so a blink does not make the tongue jump.
     */
    private fun stepWobbles(lens: Lens?, face: FaceSnapshot?, timestampNs: Long): FloatArray {
        if (lens !== wobbleLens) {
            wobbleStates.clear()
            wobbleLens = lens
            previousFace = null
        }
        val layers = lens?.art.orEmpty()
        if (wobbleAngles.size != layers.size) wobbleAngles = FloatArray(layers.size)

        // Camera timestamps are nanoseconds and monotonic, and tie the swing to the video's own
        // timeline rather than to how fast this thread happens to run. A device that reports 0
        // (or steps backwards) falls back to the wall clock rather than freezing the animation.
        val nowNs = if (timestampNs > 0L) timestampNs else System.nanoTime()
        val elapsedNs = nowNs - previousFrameNs
        val dtSeconds = if (previousFrameNs == 0L || elapsedNs <= 0L) {
            0f
        } else {
            elapsedNs / NANOS_PER_SECOND
        }
        previousFrameNs = nowNs

        // Same clamped dt as the spring, so a dropped frame cannot make the reveal jump either.
        easedOpenness = LensPhysics.ease(
            current = easedOpenness,
            target = face?.mouthOpenness ?: 0f,
            dtSeconds = dtSeconds,
            halfLifeSeconds = MOUTH_EASE_HALF_LIFE_SECONDS,
        )

        val previous = previousFace
        previousFace = face
        val shift = if (face != null && previous != null) {
            LensAnchor.faceFrame(face, face.sourceAspect)
                ?.let { LensAnchor.lateralShiftInUnits(previous, face, it, face.sourceAspect) }
                ?: 0f
        } else {
            0f
        }

        if (layers.isEmpty()) return wobbleAngles
        layers.forEachIndexed { index, art ->
            val spec = art.placement.wobble
            if (spec == null) {
                wobbleAngles[index] = 0f
                return@forEachIndexed
            }
            val stepped = LensPhysics.step(
                state = wobbleStates[index] ?: LensPhysics.Wobble.REST,
                pivotShiftInUnits = shift,
                dtSeconds = dtSeconds,
                spec = spec,
            )
            wobbleStates[index] = stepped
            wobbleAngles[index] = stepped.offsetRadians
        }
        return wobbleAngles
    }

    /**
     * Lifts one facial feature out of the camera image and blends it onto the character.
     *
     * The destination quad is placed on the character's face; the fragment shader reads the camera
     * at the subject's real feature and fades out toward an elliptical edge, so the eye or mouth
     * sits *in* the art instead of looking like a pasted rectangle of skin.
     */
    private fun drawFeature(cameraTextureId: Int, feature: FeatureQuad, frameAspect: Float) {
        writeFeatureCorners(feature, frameAspect)

        GLES20.glUseProgram(featureProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        bindAttribute(featureProgram, "aPosition", featurePositionBuffer)
        bindAttribute(featureProgram, "aLocal", featureLocalBuffer)

        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(featureProgram, "uTexMatrix"), 1, false,
            outputTextureMatrix, 0,
        )
        val cos = cos(feature.rotationRadians)
        val sin = sin(feature.rotationRadians)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(featureProgram, "uSourceCenter"),
            feature.sourceCenterX, feature.sourceCenterY,
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(featureProgram, "uSourceHalf"),
            feature.sourceHalfWidth, feature.sourceHalfHeight,
        )
        GLES20.glUniform2f(GLES20.glGetUniformLocation(featureProgram, "uSourceRight"), cos, sin)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(featureProgram, "uFrameAspect"), frameAspect)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(featureProgram, "uCamera"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Destination corners for a feature, in clip space, rotated with the head. */
    private fun writeFeatureCorners(feature: FeatureQuad, frameAspect: Float) {
        val cos = cos(feature.rotationRadians)
        val sin = sin(feature.rotationRadians)
        var index = 0
        for ((signX, signY) in CORNER_SIGNS) {
            val squareX = signX * feature.destHalfWidth
            val squareY = signY * feature.destHalfHeight
            val rotatedX = squareX * cos - squareY * sin
            val rotatedY = squareX * sin + squareY * cos
            val normalizedX = feature.destCenterX + rotatedX
            val normalizedY = feature.destCenterY + LensAnchor.fromSquareY(rotatedY, frameAspect)
            featurePositionBuffer.put(index++, normalizedX * 2f - 1f)
            featurePositionBuffer.put(index++, 1f - normalizedY * 2f)
        }
    }

    /**
     * Turns a [StickerQuad] into four clip-space corners, rotating in square space so the sticker
     * stays square on a 16:9 frame. Order matches [quadTexCoords]: TL, TR, BL, BR as a strip.
     */
    private fun writeStickerCorners(quad: StickerQuad, frameAspect: Float) {
        val cos = cos(quad.rotationRadians)
        val sin = sin(quad.rotationRadians)
        val halfWidthSquare = quad.halfWidth
        val halfHeightSquare = LensAnchor.toSquareY(quad.halfHeight, frameAspect)

        var index = 0
        for ((signX, signY) in CORNER_SIGNS) {
            val squareX = signX * halfWidthSquare
            val squareY = signY * halfHeightSquare
            val rotatedX = squareX * cos - squareY * sin
            val rotatedY = squareX * sin + squareY * cos

            val normalizedX = quad.centerX + rotatedX
            val normalizedY = quad.centerY + LensAnchor.fromSquareY(rotatedY, frameAspect)

            // Normalized (y down) → clip space (y up).
            stickerPositionBuffer.put(index++, normalizedX * 2f - 1f)
            stickerPositionBuffer.put(index++, 1f - normalizedY * 2f)
        }
    }

    /**
     * Logs each output's size and the transform CameraX handed us, once per surface.
     *
     * This is the diagnostic that answers "is the effect output rotated relative to the analysis
     * stream the face came from" — the question behind every misplaced-lens bug. The determinants
     * record handedness. Cheap, once per bind, and in the same spirit as `CameraManager`'s
     * zoom-range log.
     */
    private fun logOutputGeometryOnce(output: SurfaceOutput, size: Size) {
        if (!loggedOutputs.add(output)) return
        Log.i(
            TAG,
            "Lens output targets=${output.targets} size=${size.width}x${size.height} " +
                "inputDet=${determinant2x2(surfaceTextureMatrix)} " +
                "outputDet=${determinant2x2(outputTextureMatrix)}",
        )
    }

    // ------------------------------------------------------------------ GL plumbing

    /**
     * Brings up EGL and the two programs on first use. Returns false if the GPU refused anything —
     * the caller then declines the surface rather than letting an exception escape onto the GL
     * thread and take the process down. A camera with no lens beats a crash.
     */
    private fun ensureEgl(): Boolean {
        if (eglContext != EGL14.EGL_NO_CONTEXT) return true
        return try {
            initializeEgl()
            true
        } catch (exc: RuntimeException) {
            Log.e(TAG, "GL initialisation failed; lenses unavailable", exc)
            releaseGl()
            false
        }
    }

    private fun initializeEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // Without EGL_RECORDABLE_ANDROID the encoder input surface silently produces garbage
            // (or nothing) on many devices — the effect must render into MediaCodec, not just a view.
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0,
            ) && configCount[0] > 0,
        ) { "No suitable EGL config" }
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        // A 1×1 pbuffer gives the context somewhere to be current when no output is attached yet —
        // updateTexImage() requires a current context even if we draw nothing.
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        makeCurrentPbuffer()

        cameraProgram = buildProgram(VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        stickerProgram = buildProgram(VERTEX_SHADER, STICKER_FRAGMENT_SHADER)
        featureProgram = buildProgram(FEATURE_VERTEX_SHADER, FEATURE_FRAGMENT_SHADER)
    }

    private fun makeCurrentPbuffer() {
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
    }

    private fun createWindowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        ) ?: EGL14.EGL_NO_SURFACE

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textures[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    /** Rasterises a (vector) drawable once and uploads it as a GL texture. */
    private fun loadTexture(drawableRes: Int): Int {
        val drawable = ResourcesCompat.getDrawable(appContext.resources, drawableRes, null)
            ?: return 0
        val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(MAX_ART_PX)
        val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(MAX_ART_PX)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textures[0]
    }

    private fun bindAttribute(program: Int, name: String, buffer: FloatBuffer) {
        val location = GLES20.glGetAttribLocation(program, name)
        if (location < 0) return
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, buffer)
    }

    private fun releaseGl() {
        currentInput?.release()
        currentInput = null
        outputs.forEach { (output, eglSurface) ->
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            output.close()
        }
        outputs.clear()
        loggedOutputs.clear()
        stickerTextures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        stickerTextures.clear()
        wobbleStates.clear()
        wobbleLens = null
        previousFace = null
        previousFrameNs = 0L
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        pbufferSurface = EGL14.EGL_NO_SURFACE
    }

    /**
     * One camera input, owned jointly with CameraX: we create it, CameraX consumes it, and the
     * `provideSurface` result callback is the single point that frees it. [released] makes that
     * free idempotent so a shutdown racing CameraX's callback can't double-free the
     * [SurfaceTexture].
     */
    private class InputSurfaceState(
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        var released = false
            private set

        fun release() {
            if (released) return
            released = true
            surfaceTexture.setOnFrameAvailableListener(null)
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    private companion object {
        const val TAG = "OpenLoopLens"

        /** `EGL_RECORDABLE_ANDROID` — not exposed by [EGL14]. */
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val MATRIX_SIZE = 16

        const val VERTEX_COUNT = 4
        const val QUAD_COMPONENTS = 8
        const val MAX_ART_PX = 1024
        const val NANOS_PER_SECOND = 1_000_000_000f

        /**
         * How fast a mouth-driven layer follows the jaw. 80 ms is under a fifth of a second, so the
         * reveal still feels immediate, while smoothing the detector's frame-to-frame jitter.
         */
        const val MOUTH_EASE_HALF_LIFE_SECONDS = 0.08f

        /** Strip order: top-left, top-right, bottom-left, bottom-right (y down, before rotation). */
        val CORNER_SIGNS = arrayOf(
            -1f to -1f,
            1f to -1f,
            -1f to 1f,
            1f to 1f,
        )

        /** Pass-through vertex stage, shared by the camera and sticker programs. */
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        /**
         * Camera pass — a straight sample of the external OES texture through CameraX's transform.
         *
         * This used to carry up to two radial bulges (Big Mouth, Bug Eyes). Both lenses are gone,
         * and with them the flip into y-down screen space they needed: the shader converted
         * `vTexCoord`, warped, and converted back, which is exactly the identity when no warp
         * fires. A lens that deforms pixels again wants that scaffolding back — it is in the
         * history, not commented out here.
         */
        const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCamera;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uCamera, (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy);
            }
        """

        const val STICKER_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uSticker;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uSticker, vTexCoord);
            }
        """

        const val FEATURE_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aLocal;
            varying vec2 vLocal;
            void main() {
                gl_Position = aPosition;
                vLocal = aLocal;
            }
        """

        /**
         * Paints one of the subject's features onto the character.
         *
         * `vLocal` runs -1..1 across the destination quad. The same coordinate indexes the SOURCE
         * region around the subject's real eye or mouth, so the cut-out keeps its shape while the
         * destination is free to be a different size and place. The elliptical falloff is what
         * makes it read as part of the art rather than a rectangle of skin pasted on top.
         */
        const val FEATURE_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCamera;
            uniform mat4 uTexMatrix;
            uniform vec2 uSourceCenter;
            uniform vec2 uSourceHalf;
            uniform vec2 uSourceRight;
            uniform float uFrameAspect;
            varying vec2 vLocal;
            void main() {
                float radius = length(vLocal);
                if (radius > 1.0) discard;
                // Soft edge so the feature melts into the art instead of ending on a hard rim.
                float alpha = 1.0 - smoothstep(0.62, 1.0, radius);

                vec2 right = uSourceRight;
                vec2 up = vec2(-right.y, right.x);
                vec2 offset = right * (vLocal.x * uSourceHalf.x) + up * (vLocal.y * uSourceHalf.y);
                vec2 uv = vec2(
                    uSourceCenter.x + offset.x,
                    uSourceCenter.y + offset.y * uFrameAspect
                );

                vec2 sampleCoord = vec2(uv.x, 1.0 - uv.y);
                vec4 camera = texture2D(uCamera, (uTexMatrix * vec4(sampleCoord, 0.0, 1.0)).xy);
                gl_FragColor = vec4(camera.rgb, alpha);
            }
        """

        /**
         * 2x2 determinant of a 4x4 texture transform's upper-left block. Negative means the
         * transform flips handedness — the sign is what distinguishes "rotated" from "mirrored".
         */
        fun determinant2x2(matrix: FloatArray): Float =
            matrix[0] * matrix[5] - matrix[1] * matrix[4]

        fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertexSource))
            GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Program link failed: ${GLES20.glGetProgramInfoLog(program)}"
            }
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
            }
            return shader
        }
    }
}
