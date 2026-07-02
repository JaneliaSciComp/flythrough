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

import java.io.IOException;
import java.util.Arrays;

import org.janelia.saalfeldlab.moviemaker.imported.CLLCN;
import org.janelia.saalfeldlab.moviemaker.imported.ImageJStackOp;
import org.janelia.saalfeldlab.moviemaker.imported.Lazy;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

import bdv.util.RandomAccessibleIntervalMipmapSource;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import mpicbg.ij.clahe.Flat;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

/**
 * Builds a lazily-evaluated, optionally CLAHE/CLLCN-normalized mipmap source
 * from an N5 / OME-Zarr container.
 *
 * <p>Ported from {@code VNCMovie.createMipmapSource(...)}. Two behavioural
 * differences: the CLAHE slope is a parameter (was a hardcoded {@code 1.5f}),
 * and per-level scale / anisotropy is read from OME-NGFF {@code multiscales}
 * metadata via {@link ZarrScaleMetadata} (was a hardcoded {@code computeScales}
 * lambda + {@code anisotropyFactor}).</p>
 */
public final class MipmapSourceFactory {

	private MipmapSourceFactory() {}

	/**
	 * @param scalePrefix "" for OME-Zarr (levels named 0,1,2,…), "s" for N5 (s0,s1,…)
	 * @param clipMin/clipMax histogram clip range (only used for 16-bit sources)
	 * @param claheSlope CLAHE clip-limit slope (the old hardcoded 1.5f)
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static RandomAccessibleIntervalMipmapSource<UnsignedByteType> create(
			final String n5Path,
			final String n5Group,
			final Normalization normalization,
			final boolean invert,
			final int clipMin,
			final int clipMax,
			final double expansionFactor,
			final float claheSlope,
			final String scalePrefix) throws IOException {

		System.out.println("Opening " + n5Path + " group '" + n5Group + "'");
		final N5Reader n5 = new N5Factory().openReader(n5Path);

		// auto-detect how many scale levels exist (capped at 8)
		int numScales = 0;
		while (numScales < 8 && n5.datasetExists(n5Group + "/" + scalePrefix + numScales))
			++numScales;
		if (numScales == 0)
			throw new IOException("No scale datasets found under " + n5Path + " : " + n5Group + "/" + scalePrefix + "0..");
		System.out.println("Found " + numScales + " scale level(s) under " + n5Group + "/" + scalePrefix + "*");

		final ZarrScaleMetadata meta = ZarrScaleMetadata.read(n5, n5Group, numScales);
		final double[][] scales = meta.mipmapScales(numScales);
		final VoxelDimensions voxelDimensions = meta.voxelDimensions(expansionFactor);

		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps =
				(RandomAccessibleInterval<UnsignedByteType>[]) new RandomAccessibleInterval[numScales];

		for (int scaleIndex = 0; scaleIndex < numScales; ++scaleIndex) {

			final int scale = 1 << scaleIndex;
			final double inverseScale = 1.0 / scale;

			RandomAccessibleInterval imgRawND = N5Utils.openVolatile(n5, n5Group + "/" + scalePrefix + scaleIndex);
			// n5-zarr presents OME-Zarr as (X,Y,Z,C,T) in imglib2 order; strip trailing singleton dims to get 3D
			while (imgRawND.numDimensions() > 3)
				imgRawND = Views.hyperSlice(imgRawND, imgRawND.numDimensions() - 1, 0);
			final RandomAccessibleInterval imgRaw = imgRawND;
			RandomAccessibleInterval<UnsignedByteType> img;

			if (UnsignedByteType.class.isInstance(Views.iterable(imgRaw).firstElement())) {
				img = imgRaw;
			} else if (UnsignedShortType.class.isInstance(Views.iterable(imgRaw).firstElement())) {
				if (scaleIndex == 0)
					System.out.println("Clipping UINT16 -> UINT8 with [" + clipMin + ", " + clipMax + "] ...");

				img = Lazy.process(
						(RandomAccessibleInterval<UnsignedShortType>) imgRaw,
						new int[]{128, 128, 128},
						new UnsignedByteType(),
						AccessFlags.setOf(AccessFlags.VOLATILE),
						out -> Views.flatIterable(Views.interval(Views.pair((RandomAccessibleInterval<UnsignedShortType>) imgRaw, out), out)).forEach(
								pair -> clipToUnsignedByte(clipMin, clipMax, pair.getA(), pair.getB())));
			} else {
				throw new IOException("Unsupported type: " + Views.iterable(imgRaw).firstElement().getClass());
			}

			if (invert) {
				final RandomAccessibleInterval<UnsignedByteType> imgFinal = img;
				img = Lazy.process(
						imgFinal,
						new int[]{128, 128, 128},
						new UnsignedByteType(),
						AccessFlags.setOf(AccessFlags.VOLATILE),
						out -> Views.flatIterable(Views.interval(Views.pair(imgFinal, out), out)).forEach(
								pair -> pair.getB().set(255 - pair.getA().get())));
			}

			final int blockRadius = (int) Math.round(511 * inverseScale);

			if (normalization == Normalization.CLLCN || normalization == Normalization.CLAHE) {
				final ImageJStackOp<UnsignedByteType> op = new ImageJStackOp<>(
						Views.extendZero(img),
						normalization == Normalization.CLLCN
								? (fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true)
								: (fp) -> Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, claheSlope, null, false),
						blockRadius,
						0,
						255,
						true);
				mipmaps[scaleIndex] = Lazy.process(
						img, new int[]{128, 128, 16}, new UnsignedByteType(), AccessFlags.setOf(AccessFlags.VOLATILE), op);
			} else if (normalization == Normalization.CLAHE_WITH_THRESHOLDMASK) {
				final ImageJStackOp<UnsignedByteType> op = new ImageJStackOp<>(
						Views.extendZero(img),
						(fp) -> {
							final float[] fArray = (float[]) fp.getPixels();
							final ByteProcessor bp = new ByteProcessor(fp.getWidth(), fp.getHeight());
							boolean all0 = true;
							boolean all255 = true;
							for (int i = 0; i < fArray.length; ++i) {
								if (fArray[i] > 0) {
									bp.set(i, UnsignedByteType.getCodedSignedByte(255));
									all0 = false;
								} else {
									bp.set(i, 0);
									all255 = false;
								}
							}
							if (all0)
								return;
							else if (all255)
								Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, claheSlope, null, false);
							else
								Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, claheSlope, bp, false);
						},
						blockRadius,
						0,
						255,
						true);
				mipmaps[scaleIndex] = Lazy.process(
						img, new int[]{128, 128, 16}, new UnsignedByteType(), AccessFlags.setOf(AccessFlags.VOLATILE), op);
			} else {
				mipmaps[scaleIndex] = img;
			}

			System.out.println("s" + scaleIndex + ": scale " + Arrays.toString(scales[scaleIndex]));
		}

		return new RandomAccessibleIntervalMipmapSource<>(
				mipmaps,
				new UnsignedByteType(),
				scales,
				voxelDimensions,
				"movie");
	}

	private static void clipToUnsignedByte(final int min, final int max, final UnsignedShortType in, final UnsignedByteType out) {
		final int i = in.get();
		if (i < min)
			out.set(0);
		else if (i > max)
			out.set(255);
		else
			out.set((int) Math.round(255.0 * ((i - min) / (double) (max - min))));
	}
}
