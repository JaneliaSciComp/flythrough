/*
 * Copyright © 2026 Howard Hughes Medical Institute
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of HHMI nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.janelia.saalfeldlab.moviemaker.core;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import bdv.cache.CacheControl;
import bdv.viewer.ViewerPanel;
import bdv.viewer.ViewerState;
import bdv.viewer.animate.SimilarityTransformAnimator;
import bdv.viewer.overlay.MultiBoxOverlayRenderer;
import bdv.viewer.overlay.ScaleBarOverlayRenderer;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.PainterThread;
import bdv.viewer.render.RenderTarget;
import bdv.viewer.render.awt.BufferedImageRenderResult;
import ij.process.ColorProcessor;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Offscreen (headless) movie renderer.
 *
 * <p>Ported from {@code Fly4LICONNMovie.recordMovie(...)} and the
 * {@code accel}/{@code Target} helpers from {@code VNCMovie}. Between each pair
 * of keyframes it interpolates a {@link SimilarityTransformAnimator} with an
 * acceleration curve, paints each frame with a {@link MultiResolutionRenderer}
 * into a {@link Target}, draws the scale bar + box overlays, and writes a PNG.</p>
 */
public final class MovieRenderer {

	private MovieRenderer() {}

	/** Offscreen render target that just hands back a reusable BufferedImage result. */
	public static class Target implements RenderTarget<BufferedImageRenderResult> {

		public BufferedImageRenderResult renderResult = new BufferedImageRenderResult();

		private final int width;
		private final int height;

		public Target(final int width, final int height) {
			this.width = width;
			this.height = height;
		}

		@Override
		public BufferedImageRenderResult getReusableRenderResult() {
			return renderResult;
		}

		@Override
		public BufferedImageRenderResult createRenderResult() {
			return new BufferedImageRenderResult();
		}

		@Override
		public void setRenderResult(final BufferedImageRenderResult renderResult) {}

		@Override
		public int getWidth() {
			return width;
		}

		@Override
		public int getHeight() {
			return height;
		}
	}

	/** Cosine shape of linear [0,1]. */
	private static double cos(final double x) {
		return 0.5 - 0.5 * Math.cos(Math.PI * x);
	}

	/**
	 * Acceleration function for t in [0,1]:
	 * <pre>
	 *   0  symmetric
	 *   1  slow start
	 *   2  slow end
	 *   3  soft symmetric
	 *   4  soft slow start
	 *   5  soft slow end
	 * </pre>
	 */
	public static double accel(final double t, final int type) {
		switch (type) {
		case 1: // slow start
			return cos(t * t);
		case 2: // slow end
			return 1.0 - cos(Math.pow(1.0 - t, 2));
		case 3: // soft symmetric
			return cos(cos(t));
		case 4: // soft slow start
			return cos(cos(t * t));
		case 5: // soft slow end
			return 1.0 - cos(cos(Math.pow(1.0 - t, 2)));
		default: // symmetric
			return cos(t);
		}
	}

	public static final String[] ACCEL_NAMES = {
			"symmetric", "slow start", "slow end", "soft symmetric", "soft slow start", "soft slow end"
	};

	/** viewer-to-canvas recentring transform: translate so (0,0) lands at the canvas centre. */
	private static AffineTransform3D recentre(final int width, final int height) {
		final AffineTransform3D t = new AffineTransform3D();
		t.set(1, 0, 0, 0.5 * width,
		      0, 1, 0, 0.5 * height,
		      0, 0, 1, 0);
		return t;
	}

	/**
	 * Render the whole movie as a PNG sequence into {@code dir}.
	 *
	 * @param transforms keyframe feed transforms (canvas-centre translation removed)
	 * @param frames per-segment frame counts; {@code frames[k]} = motion into transforms[k]
	 * @param accel per-segment acceleration types
	 * @param firstTransformIndex index of the first keyframe to render motion into (usually 1)
	 */
	public static void recordMovie(
			final ViewerPanel viewer,
			final int width,
			final int height,
			final AffineTransform3D[] transforms,
			final int[] frames,
			final int[] accel,
			final int firstTransformIndex,
			final String dir) throws IOException {

		// write the PNG frames into an "images" subfolder so they stay separate
		// from movie-config.json (which lives at the top of the movie dir)
		final File imagesDir = new File(dir, "images");
		imagesDir.mkdirs();

		final ViewerState renderState = viewer.state();
		final ScaleBarOverlayRenderer scalebar = new ScaleBarOverlayRenderer();
		final MultiBoxOverlayRenderer box = new MultiBoxOverlayRenderer(width, height);
		final AffineTransform3D recentre = recentre(width, height);

		final Target target = new Target(width, height);
		final MultiResolutionRenderer renderer = newRenderer(target);

		// keyframes are stored as source->screen; convert to world->screen for THIS
		// viewer's source placement so framing matches the interactive capture.
		final AffineTransform3D[] world = toWorldTransforms(viewer, transforms);

		/* count frame index up to firstTransformIndex */
		int i = 0;
		for (int k = 0; k < firstTransformIndex; ++k)
			i += frames[k];

		for (int k = firstTransformIndex; k < transforms.length; ++k) {
			final SimilarityTransformAnimator animator = new SimilarityTransformAnimator(
					world[k - 1],
					world[k],
					width / 2.0,
					height / 2.0,
					0);

			for (int d = 0; d < frames[k]; ++d) {
				final AffineTransform3D tkd = animator.get(accel((double) d / (double) frames[k], accel[k]));
				tkd.preConcatenate(recentre.inverse());
				tkd.preConcatenate(recentre);
				final BufferedImage bi = paint(renderer, target, renderState, viewer, tkd, scalebar, box, width, height);

				ImageIO.write(bi, "png", new File(imagesDir, String.format("img-%04d.png", i++)));
				System.out.println(new File(imagesDir, String.format("img-%04d.png", i)));
			}
		}
	}

	/**
	 * Render a single frame at one keyframe (for verify-window thumbnails), using
	 * the same offscreen path as {@link #recordMovie} so the preview matches output.
	 *
	 * <p>Backed by a <em>non-volatile</em> source, {@link MultiResolutionRenderer#paint}
	 * renders synchronously and completely in a single call (as in the hot-knife
	 * Fly4/Retina movie classes), so we paint once — no repaint loop.</p>
	 */
	public static BufferedImage renderSingleFrame(
			final ViewerPanel viewer,
			final int width,
			final int height,
			final AffineTransform3D keyTransform) {

		final ViewerState renderState = viewer.state();
		final ScaleBarOverlayRenderer scalebar = new ScaleBarOverlayRenderer();
		final MultiBoxOverlayRenderer box = new MultiBoxOverlayRenderer(width, height);
		final AffineTransform3D recentre = recentre(width, height);

		final Target target = new Target(width, height);
		final MultiResolutionRenderer renderer = newRenderer(target);

		// keyframe is source->screen; convert to world->screen for this viewer's source
		final AffineTransform3D world = toWorld(viewer, keyTransform);

		// route the keyframe through the animator centring, exactly like a movie frame
		final AffineTransform3D tkd = new SimilarityTransformAnimator(world, world, width / 2.0, height / 2.0, 0).get(0);
		tkd.preConcatenate(recentre.inverse());
		tkd.preConcatenate(recentre);

		return paint(renderer, target, renderState, viewer, tkd, scalebar, box, width, height);
	}

	/** Read the level-0 source transform of the viewer's first source (identity if unavailable). */
	private static AffineTransform3D sourceTransform(final ViewerPanel viewer) {
		final AffineTransform3D t = new AffineTransform3D();
		try {
			viewer.state().getSources().get(0).getSpimSource().getSourceTransform(0, 0, t);
		} catch (final Exception e) {
			// leave identity
		}
		return t;
	}

	/** Convert a stored source&rarr;screen keyframe to a world&rarr;screen viewer transform. */
	private static AffineTransform3D toWorld(final ViewerPanel viewer, final AffineTransform3D sourceToScreen) {
		return sourceToScreen.copy().concatenate(sourceTransform(viewer).inverse());
	}

	private static AffineTransform3D[] toWorldTransforms(final ViewerPanel viewer, final AffineTransform3D[] sourceToScreen) {
		final AffineTransform3D inv = sourceTransform(viewer).inverse();
		final AffineTransform3D[] out = new AffineTransform3D[sourceToScreen.length];
		for (int k = 0; k < out.length; ++k)
			out[k] = sourceToScreen[k] == null ? null : sourceToScreen[k].copy().concatenate(inv);
		return out;
	}

	private static MultiResolutionRenderer newRenderer(final Target target) {
		return new MultiResolutionRenderer(
				target,
				new PainterThread(null),
				new double[]{1.0},
				0L,
				32,
				null,
				false,
				new CacheControl.Dummy());
	}

	private static BufferedImage paint(
			final MultiResolutionRenderer renderer,
			final Target target,
			final ViewerState renderState,
			final ViewerPanel viewer,
			final AffineTransform3D tkd,
			final ScaleBarOverlayRenderer scalebar,
			final MultiBoxOverlayRenderer box,
			final int width,
			final int height) {

		viewer.state().setViewerTransform(tkd);
		renderState.setViewerTransform(tkd);
		renderer.requestRepaint();
		renderer.paint(renderState);

		final BufferedImage bi = target.renderResult.getBufferedImage();
		final ColorProcessor ip = new ColorProcessor(bi);
		final Graphics2D g2 = bi.createGraphics();
		g2.drawImage(ip.createImage(), 0, 0, null);

		g2.setClip(0, 0, width, height);
		scalebar.setViewerState(renderState);
		scalebar.paint(g2);
		box.setViewerState(renderState);
		box.paint(g2);

		return bi;
	}
}
