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
package org.janelia.saalfeldlab.moviemaker;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.saalfeldlab.moviemaker.core.Normalization;
import org.janelia.saalfeldlab.moviemaker.core.ViewTransforms.KeyPoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.imglib2.realtransform.AffineTransform3D;

/**
 * The movie definition: dataset + display parameters, ordered key points, and
 * per-segment timing. Serialized to / from a hand-editable JSON file with Gson.
 */
public class MovieConfig {

	/** Motion into a key point: number of frames and acceleration type (0-5). */
	public static class Segment {
		public int frames;
		public int accel;

		public Segment() {}

		public Segment(final int frames, final int accel) {
			this.frames = frames;
			this.accel = accel;
		}
	}

	// --- dataset ---
	public String dataPath = "";
	public String dataGroup = "";       // "" for a root OME-Zarr
	public String scalePrefix = "";     // "" for OME-Zarr (0,1,2…), "s" for N5 (s0,s1…)
	public String moviePath = "";

	// --- display ---
	public double expansionFactor = 1.0;
	public String normalization = Normalization.CLAHE.name();
	public float claheSlope = 1.5f;
	public int histogramMin = 0;
	public int histogramMax = 65535;
	public boolean invert = false;
	public int screenWidth = 1050;
	public int screenHeight = 750;
	public int fps = 30;

	// --- timeline ---
	public int holdFirstFrames = 0;
	public boolean returnToFirst = false;
	public int returnFrames = 0;
	public int returnAccel = 0;

	public List<KeyPoint> keyPoints = new ArrayList<>();
	/** segments[k] = motion from keyPoints[k-1] to keyPoints[k]; segments[0] is unused. */
	public List<Segment> segments = new ArrayList<>();

	public Normalization normalizationEnum() {
		try {
			return Normalization.valueOf(normalization);
		} catch (final Exception e) {
			return Normalization.CLAHE;
		}
	}

	/** Ensure there is exactly one segment per key point (segments[0] a placeholder). */
	public void syncSegments() {
		while (segments.size() < keyPoints.size())
			segments.add(new Segment(120, 0));
		while (segments.size() > keyPoints.size())
			segments.remove(segments.size() - 1);
	}

	// ---- Gson ----

	private static Gson gson() {
		return new GsonBuilder().setPrettyPrinting().create();
	}

	public void save(final File file) throws IOException {
		file.getAbsoluteFile().getParentFile().mkdirs();
		try (final FileWriter w = new FileWriter(file)) {
			gson().toJson(this, w);
		}
	}

	public static MovieConfig load(final File file) throws IOException {
		try (final FileReader r = new FileReader(file)) {
			final MovieConfig c = gson().fromJson(r, MovieConfig.class);
			if (c.keyPoints == null)
				c.keyPoints = new ArrayList<>();
			if (c.segments == null)
				c.segments = new ArrayList<>();
			return c;
		}
	}

	/**
	 * Expanded keyframe timeline ready for
	 * {@link org.janelia.saalfeldlab.moviemaker.core.MovieRenderer#recordMovie}.
	 */
	public static class Timeline {
		public AffineTransform3D[] transforms;
		public int[] frames;
		public int[] accel;
		public int firstTransformIndex = 1;

		public int totalFrames() {
			int sum = 0;
			for (int k = firstTransformIndex; k < frames.length; ++k)
				sum += frames[k];
			return sum;
		}
	}

	/**
	 * Expand key points + segments (+ optional hold and return-to-first) into the
	 * transforms / frames / accel arrays.
	 *
	 * <p>Layout: [anchor K0] [hold K0] [K1] … [Kn-1] ([return K0]). The anchor at
	 * index 0 is never rendered; motion is rendered from index 1 onward.</p>
	 */
	public Timeline buildTimeline() {
		if (keyPoints.isEmpty())
			throw new IllegalStateException("no key points");
		syncSegments();

		final List<AffineTransform3D> t = new ArrayList<>();
		final List<Integer> f = new ArrayList<>();
		final List<Integer> a = new ArrayList<>();

		final AffineTransform3D first = keyPoints.get(0).toTransform();

		// index 0: anchor (not rendered)
		t.add(first.copy());
		f.add(0);
		a.add(0);

		// index 1: hold on the first key point (static frames)
		t.add(first.copy());
		f.add(Math.max(0, holdFirstFrames));
		a.add(0);

		// motion through the remaining key points
		for (int k = 1; k < keyPoints.size(); ++k) {
			t.add(keyPoints.get(k).toTransform());
			final Segment s = segments.get(k);
			f.add(Math.max(0, s.frames));
			a.add(clampAccel(s.accel));
		}

		// optional return to the first key point
		if (returnToFirst) {
			t.add(first.copy());
			f.add(Math.max(0, returnFrames));
			a.add(clampAccel(returnAccel));
		}

		final Timeline tl = new Timeline();
		tl.transforms = t.toArray(new AffineTransform3D[0]);
		tl.frames = f.stream().mapToInt(Integer::intValue).toArray();
		tl.accel = a.stream().mapToInt(Integer::intValue).toArray();
		tl.firstTransformIndex = 1;
		return tl;
	}

	private static int clampAccel(final int accel) {
		return Math.max(0, Math.min(5, accel));
	}
}
