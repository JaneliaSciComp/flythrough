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

import bdv.viewer.ViewerPanel;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Keyframe camera positions and the math that captures / rebuilds them.
 *
 * <p>Ported from {@code Fly4LICONNMovie.viewCentredOn} and its interactive
 * "T" capture handler. Keyframes are stored as a world point + zoom
 * ({@link KeyPoint}) rather than a raw matrix, which keeps them independent of
 * the screen / canvas size.</p>
 */
public final class ViewTransforms {

	private ViewTransforms() {}

	/**
	 * A keyframe: the world point (wx,wy,wz) that should sit at the screen
	 * centre, and the zoom (screen pixels per world unit).
	 */
	public static final class KeyPoint {

		public double wx;
		public double wy;
		public double wz;
		public double scale;

		public KeyPoint() {}

		public KeyPoint(final double wx, final double wy, final double wz, final double scale) {
			this.wx = wx;
			this.wy = wy;
			this.wz = wz;
			this.scale = scale;
		}

		public AffineTransform3D toTransform() {
			return viewCenteredOn(wx, wy, wz, scale);
		}

		@Override
		public String toString() {
			return String.format("viewCenteredOn(%.1f, %.1f, %.1f, %.6f)", wx, wy, wz, scale);
		}
	}

	/**
	 * Build a movie-space viewer transform centred on the world point (wx,wy,wz)
	 * at the given zoom (screen pixels per world unit). The point lands at the
	 * screen centre and on the current z-slice, so keyframes are defined by WHERE
	 * in the volume you look and HOW zoomed - never by where the cursor happened
	 * to be. Reproducible and edge-safe.
	 *
	 * <p>NOTE: the world point is placed at the ORIGIN (0,0), not the screen
	 * centre. {@code MovieRenderer} feeds keyframes through
	 * {@code SimilarityTransformAnimator.get()}, which adds
	 * (cX,cY) = (width/2, height/2) to the translation. So an origin-centred
	 * transform ends up centred on screen; a screen-centred one would be shoved
	 * an extra half-screen into the bottom-right corner.</p>
	 */
	public static AffineTransform3D viewCenteredOn(
			final double wx,
			final double wy,
			final double wz,
			final double scale) {

		final AffineTransform3D t = new AffineTransform3D();
		t.set(scale, 0, 0, -scale * wx,
		      0, scale, 0, -scale * wy,
		      0, 0, scale, -scale * wz);
		return t;
	}

	/**
	 * Capture the viewer's current view as a {@link KeyPoint}.
	 *
	 * <p>BDV returns the transform in the panel's logical pixel coordinates, and
	 * the offline movie renderer also works in logical pixels - so there is no
	 * HiDPI rescaling to do. The only adjustment is recentring from the
	 * interactive panel centre to the movie canvas centre, which can differ in
	 * size (e.g. a 1050x700 panel vs a 1050x750 movie).</p>
	 */
	public static KeyPoint capture(final ViewerPanel vp, final int movieWidth, final int movieHeight) {

		final AffineTransform3D transform = new AffineTransform3D();
		vp.state().getViewerTransform(transform);

		final double corrX = movieWidth  / 2.0 - vp.getWidth()  / 2.0;
		final double corrY = movieHeight / 2.0 - vp.getHeight() / 2.0;
		transform.set(transform.get(0, 3) + corrX, 0, 3);
		transform.set(transform.get(1, 3) + corrY, 1, 3);

		// screen pixels per world unit
		final double sx = Math.hypot(transform.get(0, 0), transform.get(0, 1));
		// world point currently at the movie-canvas centre
		final double[] centre = new double[3];
		transform.applyInverse(centre, new double[]{movieWidth / 2.0, movieHeight / 2.0, 0});

		return new KeyPoint(centre[0], centre[1], centre[2], sx);
	}
}
