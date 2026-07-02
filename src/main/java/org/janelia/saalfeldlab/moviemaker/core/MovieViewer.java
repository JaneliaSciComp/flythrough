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
