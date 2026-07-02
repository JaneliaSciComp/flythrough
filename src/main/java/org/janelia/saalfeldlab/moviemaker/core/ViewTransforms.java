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
 * <p>A keyframe stores the full viewer transform (12 row-packed doubles) so that
 * rotation and any orientation are preserved — not just a centre + zoom.</p>
 */
public final class ViewTransforms {

	private ViewTransforms() {}

	/**
	 * A keyframe: the full "feed" viewer transform.
	 *
	 * <p>The stored transform is the movie-space viewer transform with the
	 * canvas-centre translation removed, so that when fed through
	 * {@code SimilarityTransformAnimator} (which adds the canvas centre back at
	 * render time) it reproduces the exact captured view — independent of the
	 * movie canvas size.</p>
	 */
	public static final class KeyPoint {

		/** Full feed transform, 12 row-packed doubles (m00,m01,m02,m03,m10,…,m23). */
		public double[] transform;

		public KeyPoint() {}

		public static KeyPoint of(final AffineTransform3D feed) {
			final KeyPoint kp = new KeyPoint();
			kp.transform = feed.getRowPackedCopy();
			return kp;
		}

		public AffineTransform3D toTransform() {
			final AffineTransform3D t = new AffineTransform3D();
			if (transform != null && transform.length == 12)
				t.set(transform);
			return t;
		}

		/** Zoom (screen pixels per world unit) for display. */
		public double displayScale() {
			final AffineTransform3D t = toTransform();
			return Math.hypot(t.get(0, 0), t.get(0, 1));
		}

		/** World point at the canvas centre, for display / labels. */
		public double[] displayCenter() {
			final double[] c = new double[3];
			toTransform().applyInverse(c, new double[]{0, 0, 0});
			return c;
		}

		@Override
		public String toString() {
			final double[] c = displayCenter();
			return String.format("centre (%.1f, %.1f, %.1f) zoom %.6f", c[0], c[1], c[2], displayScale());
		}
	}

	/**
	 * Capture the viewer's current view as a {@link KeyPoint}, preserving the full
	 * orientation (including rotation).
	 *
	 * <p>The panel's viewer transform maps the source to panel-screen pixels; we
	 * subtract the panel-centre translation so the transform is expressed relative
	 * to the canvas centre. {@code SimilarityTransformAnimator} (with rotation
	 * centre = movie-canvas centre) then adds the movie canvas centre back at
	 * render time, so the exact view is reproduced regardless of any difference
	 * between the interactive panel size and the movie canvas size.</p>
	 */
	public static KeyPoint capture(final ViewerPanel vp) {

		final AffineTransform3D t = new AffineTransform3D();
		vp.state().getViewerTransform(t);
		t.set(t.get(0, 3) - vp.getWidth() / 2.0, 0, 3);
		t.set(t.get(1, 3) - vp.getHeight() / 2.0, 1, 3);
		return KeyPoint.of(t);
	}
}
