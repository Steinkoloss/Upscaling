package io.github.steinkoloss.upscaling;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Camera matrices for the current and previous frame, used for jitter and for
 * reconstructing camera motion from depth.
 *
 * <p>Minecraft renders the world relative to the camera position: clip =
 * P * R * (worldPos - cameraPos), where P is the projection (including view bobbing
 * and nausea warp) and R the view rotation. "ViewProj" below means P * R.
 */
public final class FrameState {
	private static final Matrix4f currViewProj = new Matrix4f();
	private static final Matrix4f currViewProjJittered = new Matrix4f();
	private static final Matrix4f prevViewProj = new Matrix4f();
	private static @Nullable Vec3 currCameraPos;
	private static @Nullable Vec3 prevCameraPos;
	private static int currWidth;
	private static int currHeight;
	private static boolean hasPrevious;

	private FrameState() {
	}

	/**
	 * Records this frame's projection and camera and returns the projection the
	 * world should actually be rendered with (jittered when {@code jitter} is set).
	 */
	public static Matrix4f beginFrame(Matrix4f projection, CameraRenderState camera, int renderWidth, int renderHeight, int displayWidth, boolean jitter) {
		if (renderWidth != currWidth || renderHeight != currHeight) {
			// Resized scene target: the previous frame no longer lines up.
			hasPrevious = false;
		}
		currWidth = renderWidth;
		currHeight = renderHeight;
		currCameraPos = camera.pos;
		currViewProj.set(projection).mul(camera.viewRotationMatrix);

		Matrix4f rendered = projection;
		if (jitter) {
			Jitter.advance(renderWidth, displayWidth);
			// Shift in NDC: one pixel is 2 / size.
			rendered = new Matrix4f()
					.translation(2.0f * Jitter.x() / renderWidth, 2.0f * Jitter.y() / renderHeight, 0.0f)
					.mul(projection);
		}
		currViewProjJittered.set(rendered).mul(camera.viewRotationMatrix);
		return rendered;
	}

	public static void endFrame() {
		prevViewProj.set(currViewProj);
		prevCameraPos = currCameraPos;
		hasPrevious = true;
	}

	/** Drops history, e.g. when upscaling is toggled. */
	public static void reset() {
		hasPrevious = false;
	}

	public static boolean hasPrevious() {
		return hasPrevious && prevCameraPos != null;
	}

	public static Matrix4f currViewProj() {
		return currViewProj;
	}

	public static Matrix4f currViewProjJittered() {
		return currViewProjJittered;
	}

	public static Matrix4f prevViewProj() {
		return hasPrevious() ? prevViewProj : currViewProj;
	}

	/** Current minus previous camera position, in blocks. */
	public static Vec3 cameraDelta() {
		if (!hasPrevious() || currCameraPos == null) {
			return Vec3.ZERO;
		}
		return currCameraPos.subtract(prevCameraPos);
	}
}
