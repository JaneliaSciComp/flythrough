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
package org.janelia.flythrough.core;

import java.util.ArrayList;
import java.util.List;

import org.janelia.saalfeldlab.n5.N5Reader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;

/**
 * Reads per-scale-level physical spacing (and hence anisotropy and pyramid
 * downsampling) from a dataset's OME-NGFF {@code multiscales} metadata, so that
 * neither an anisotropy constant nor a hand-written {@code computeScales} lambda
 * is needed.
 *
 * <p>Spacing arrays are returned in imglib2 axis order (x, y, z) — i.e. the
 * reverse of the stored/NGFF order (…, z, y, x) — to match what
 * {@code N5Utils.open} presents after trailing singleton (c, t) dimensions are
 * stripped.</p>
 */
public final class ZarrScaleMetadata {

	/** Per-level physical spacing in imglib2 order {x, y, z}; index = scale level. */
	public final double[][] levelSpacingXYZ;

	/** Physical unit reported by the metadata (e.g. "micrometer"); may be "px" for the fallback. */
	public final String unit;

	/** True when the numbers came from real metadata rather than the isotropic fallback. */
	public final boolean fromMetadata;

	private ZarrScaleMetadata(final double[][] levelSpacingXYZ, final String unit, final boolean fromMetadata) {
		this.levelSpacingXYZ = levelSpacingXYZ;
		this.unit = unit;
		this.fromMetadata = fromMetadata;
	}

	/**
	 * Read the multiscale metadata for {@code group} and produce at least
	 * {@code numLevels} spacing entries. On any failure (missing / unparseable
	 * metadata) an isotropic, factor-2-per-level fallback is returned.
	 */
	public static ZarrScaleMetadata read(final N5Reader n5, final String group, final int numLevels) {

		try {
			final JsonElement multiscales = findMultiscales(n5, group);
			if (multiscales != null && multiscales.isJsonArray() && multiscales.getAsJsonArray().size() > 0) {
				final JsonObject ms = multiscales.getAsJsonArray().get(0).getAsJsonObject();
				final double[][] spacing = parseSpacing(ms);
				if (spacing != null && spacing.length > 0) {
					final String u = parseUnit(ms);
					return new ZarrScaleMetadata(spacing, u, true);
				}
			}
		} catch (final Exception e) {
			System.out.println("ZarrScaleMetadata: could not parse OME-NGFF multiscales (" + e + "); using isotropic fallback.");
		}

		System.out.println("ZarrScaleMetadata: no usable multiscales metadata; assuming isotropic spacing with factor-2 downsampling per level.");
		final double[][] spacing = new double[Math.max(1, numLevels)][3];
		for (int l = 0; l < spacing.length; ++l) {
			final double f = 1 << l;
			spacing[l] = new double[]{f, f, f};
		}
		return new ZarrScaleMetadata(spacing, "px", false);
	}

	private static JsonElement findMultiscales(final N5Reader n5, final String group) {

		final String g = (group == null || group.isEmpty()) ? "/" : group;

		JsonElement ms = getAttribute(n5, g, "multiscales");
		if (ms != null && ms.isJsonArray())
			return ms;

		// OME-Zarr v3 (OME 0.5) nests everything under an "ome" object
		final JsonElement ome = getAttribute(n5, g, "ome");
		if (ome != null && ome.isJsonObject()) {
			final JsonElement nested = ome.getAsJsonObject().get("multiscales");
			if (nested != null && nested.isJsonArray())
				return nested;
		}
		return null;
	}

	private static JsonElement getAttribute(final N5Reader n5, final String group, final String key) {
		try {
			return n5.getAttribute(group, key, JsonElement.class);
		} catch (final Exception e) {
			return null;
		}
	}

	/**
	 * @return per-level spacing in imglib2 (x,y,z) order, or null if unparseable.
	 */
	private static double[][] parseSpacing(final JsonObject ms) {

		final JsonArray datasets = ms.getAsJsonArray("datasets");
		if (datasets == null || datasets.size() == 0)
			return null;

		// spatial axis indices (in NGFF order); default to the last 3 if no axes block
		final int[] spatialAxes = spatialAxisIndices(ms, firstScaleLength(datasets));

		// optional multiscale-level scale transform, applied to every level
		final double[] globalScale = firstScaleTransform(ms.getAsJsonArray("coordinateTransformations"));

		final List<double[]> out = new ArrayList<>();
		for (int i = 0; i < datasets.size(); ++i) {
			final JsonObject ds = datasets.get(i).getAsJsonObject();
			final double[] scale = firstScaleTransform(ds.getAsJsonArray("coordinateTransformations"));
			if (scale == null)
				return null;

			if (globalScale != null)
				for (int a = 0; a < scale.length && a < globalScale.length; ++a)
					scale[a] *= globalScale[a];

			// pick spatial axes in NGFF order (…, z, y, x) then reverse to (x, y, z)
			final double[] spatialNgff = new double[spatialAxes.length];
			for (int s = 0; s < spatialAxes.length; ++s)
				spatialNgff[s] = scale[spatialAxes[s]];

			final double[] xyz = new double[3];
			final int n = spatialNgff.length;
			// map the last 3 spatial axes (z,y,x) -> (x,y,z)
			xyz[0] = spatialNgff[n - 1];
			xyz[1] = spatialNgff[n - 2];
			xyz[2] = spatialNgff[n - 3];
			out.add(xyz);
		}
		return out.toArray(new double[0][]);
	}

	private static int firstScaleLength(final JsonArray datasets) {
		final double[] s = firstScaleTransform(datasets.get(0).getAsJsonObject().getAsJsonArray("coordinateTransformations"));
		return s == null ? 3 : s.length;
	}

	/** Extract the {@code scale} array from a coordinateTransformations list. */
	private static double[] firstScaleTransform(final JsonArray transforms) {
		if (transforms == null)
			return null;
		for (int i = 0; i < transforms.size(); ++i) {
			final JsonObject t = transforms.get(i).getAsJsonObject();
			if (t.has("type") && "scale".equals(t.get("type").getAsString()) && t.has("scale")) {
				final JsonArray a = t.getAsJsonArray("scale");
				final double[] s = new double[a.size()];
				for (int k = 0; k < s.length; ++k)
					s[k] = a.get(k).getAsDouble();
				return s;
			}
		}
		return null;
	}

	/** Indices of spatial ("space") axes in NGFF order, or the last 3 if no axes block. */
	private static int[] spatialAxisIndices(final JsonObject ms, final int numAxes) {
		final JsonArray axes = ms.getAsJsonArray("axes");
		if (axes != null && axes.size() > 0) {
			final List<Integer> spatial = new ArrayList<>();
			for (int i = 0; i < axes.size(); ++i) {
				final JsonObject ax = axes.get(i).getAsJsonObject();
				final String type = ax.has("type") ? ax.get("type").getAsString() : "space";
				if ("space".equals(type))
					spatial.add(i);
			}
			if (spatial.size() >= 3) {
				final int[] idx = new int[spatial.size()];
				for (int i = 0; i < idx.length; ++i)
					idx[i] = spatial.get(i);
				return idx;
			}
		}
		// fallback: last 3 axes
		return new int[]{numAxes - 3, numAxes - 2, numAxes - 1};
	}

	private static String parseUnit(final JsonObject ms) {
		final JsonArray axes = ms.getAsJsonArray("axes");
		if (axes != null) {
			for (int i = axes.size() - 1; i >= 0; --i) {
				final JsonObject ax = axes.get(i).getAsJsonObject();
				final String type = ax.has("type") ? ax.get("type").getAsString() : "space";
				if ("space".equals(type) && ax.has("unit"))
					return ax.get("unit").getAsString();
			}
		}
		return "px";
	}

	/**
	 * Downsampling factors for the {@link bdv.util.RandomAccessibleIntervalMipmapSource},
	 * normalized so that level-0 X equals 1. Level 0 therefore becomes
	 * {@code {1, 1, anisotropy}} and finer levels carry the pyramid factors, exactly
	 * reproducing the old hardcoded {@code computeScales} behaviour.
	 */
	public double[][] mipmapScales(final int numLevels) {
		final double ref = levelSpacingXYZ[0][0];
		final double[][] scales = new double[numLevels][3];
		for (int l = 0; l < numLevels; ++l) {
			final double[] sp = levelSpacingXYZ[Math.min(l, levelSpacingXYZ.length - 1)];
			scales[l] = new double[]{sp[0] / ref, sp[1] / ref, sp[2] / ref};
		}
		return scales;
	}

	/**
	 * Physical voxel size for the scale bar: the level-0 spacing divided by the
	 * (biological) expansion factor.
	 */
	public VoxelDimensions voxelDimensions(final double expansionFactor) {
		final double[] s0 = levelSpacingXYZ[0];
		return new FinalVoxelDimensions(
				unit,
				new double[]{s0[0] / expansionFactor, s0[1] / expansionFactor, s0[2] / expansionFactor});
	}
}
