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

import java.awt.Window;
import java.io.IOException;

import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.moviemaker.MovieConfig;

import bdv.cache.SharedQueue;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;

/**
 * Builds the {@link RandomAccessibleIntervalMipmapSource} from a
 * {@link MovieConfig} and shows it in a BigDataViewer window, configured
 * identically for interactive navigation and for offscreen rendering.
 */
public final class MovieViewer {

	private MovieViewer() {}

	public static BdvStackSource<?> show(final MovieConfig cfg, final boolean interactive) throws IOException {

		final RandomAccessibleIntervalMipmapSource<UnsignedByteType> source = MipmapSourceFactory.create(
				cfg.dataPath,
				cfg.dataGroup,
				cfg.normalizationEnum(),
				cfg.invert,
				cfg.histogramMin,
				cfg.histogramMax,
				cfg.expansionFactor,
				cfg.claheSlope,
				cfg.scalePrefix);

		final int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

		final BdvStackSource<?> bdv;
		if (interactive) {
			final SharedQueue queue = new SharedQueue(threads);
			bdv = BdvFunctions.show((Source) source.asVolatile(queue), BdvOptions.options().numRenderingThreads(threads));
		} else {
			bdv = BdvFunctions.show(source, BdvOptions.options().numRenderingThreads(threads));
		}

		bdv.setColor(new ARGBType(ARGBType.rgba(255, 255, 255, 255)));
		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.setDisplayRange(0, 255);

		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		if (frame != null)
			frame.setSize(cfg.screenWidth, cfg.screenHeight);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(cfg.screenWidth, cfg.screenHeight);

		return bdv;
	}
}
