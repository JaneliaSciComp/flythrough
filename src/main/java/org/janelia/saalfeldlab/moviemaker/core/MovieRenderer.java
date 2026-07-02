/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
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

		new File(dir).mkdirs();

		final ViewerState renderState = viewer.state();
		final ScaleBarOverlayRenderer scalebar = new ScaleBarOverlayRenderer();
		final MultiBoxOverlayRenderer box = new MultiBoxOverlayRenderer(width, height);
		final AffineTransform3D recentre = recentre(width, height);

		final Target target = new Target(width, height);
		final MultiResolutionRenderer renderer = newRenderer(target);

		/* count frame index up to firstTransformIndex */
		int i = 0;
		for (int k = 0; k < firstTransformIndex; ++k)
			i += frames[k];

		for (int k = firstTransformIndex; k < transforms.length; ++k) {
			final SimilarityTransformAnimator animator = new SimilarityTransformAnimator(
					transforms[k - 1],
					transforms[k],
					width / 2.0,
					height / 2.0,
					0);

			for (int d = 0; d < frames[k]; ++d) {
				final AffineTransform3D tkd = animator.get(accel((double) d / (double) frames[k], accel[k]));
				tkd.preConcatenate(recentre.inverse());
				tkd.preConcatenate(recentre);
				final BufferedImage bi = paint(renderer, target, renderState, viewer, tkd, scalebar, box, width, height);

				ImageIO.write(bi, "png", new File(String.format("%s/img-%04d.png", dir, i++)));
				System.out.println(String.format("%s/img-%04d.png", dir, i));
			}
		}
	}

	/**
	 * Render a single frame at one keyframe (for verify-window thumbnails), using
	 * the same offscreen path as {@link #recordMovie} so the preview matches output.
	 *
	 * <p>The viewer may be backed by a <em>volatile</em> source whose cells load
	 * asynchronously; {@link MultiResolutionRenderer#paint} returns {@code false}
	 * while data is still missing. We therefore repaint until it reports the frame
	 * is fully resolved (or a timeout elapses), so thumbnails are not captured
	 * black before the data has loaded.</p>
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

		// route the keyframe through the animator centring, exactly like a movie frame
		final AffineTransform3D tkd = new SimilarityTransformAnimator(keyTransform, keyTransform, width / 2.0, height / 2.0, 0).get(0);
		tkd.preConcatenate(recentre.inverse());
		tkd.preConcatenate(recentre);

		viewer.state().setViewerTransform(tkd);
		renderState.setViewerTransform(tkd);

		boolean valid = false;
		for (int attempt = 0; attempt < 400 && !valid; ++attempt) {
			renderer.requestRepaint();
			valid = renderer.paint(renderState);
			if (!valid) {
				try {
					Thread.sleep(25);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

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
