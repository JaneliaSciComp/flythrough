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
import java.util.concurrent.Callable;

import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.moviemaker.MovieConfig.Timeline;
import org.janelia.saalfeldlab.moviemaker.core.MovieRenderer;
import org.janelia.saalfeldlab.moviemaker.core.MovieViewer;
import org.janelia.saalfeldlab.moviemaker.gui.SetupPanel;

import bdv.util.BdvStackSource;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Entry point.
 *
 * <ul>
 *   <li>No arguments: launch the authoring GUI (setup &rarr; navigate &rarr; verify).</li>
 *   <li>{@code --config <file>}: headless render of a saved config to a PNG sequence.</li>
 * </ul>
 */
@Command(name = "movie-maker", mixinStandardHelpOptions = true,
		description = "Author and render fly-through movies of 3D microscopy volumes (N5 / OME-Zarr).")
public class MovieMaker implements Callable<Void> {

	@Option(names = "--config", description = "Render this movie config JSON headlessly (no GUI).")
	private File config;

	public static void main(final String... args) {
		new CommandLine(new MovieMaker()).execute(args);
	}

	@Override
	public Void call() throws Exception {
		if (config != null)
			renderHeadless(config);
		else
			SwingUtilities.invokeLater(() -> new SetupPanel().setVisible(true));
		return null;
	}

	/** Load a config and render its PNG sequence, then exit. */
	public static void renderHeadless(final File configFile) throws Exception {

		final MovieConfig cfg = MovieConfig.load(configFile);
		System.out.println("Loaded config: " + cfg.keyPoints.size() + " key point(s)");
		render(cfg);
		System.out.println("Done.");
		System.exit(0);
	}

	/**
	 * Render a config to a PNG sequence using a fresh non-volatile viewer (so
	 * frames are fully resolved). Does not exit the JVM, so the GUI can call it.
	 */
	public static void render(final MovieConfig cfg) throws Exception {

		final BdvStackSource<?> bdv = MovieViewer.show(cfg, false);

		// give the viewer a moment to initialise before offscreen rendering
		Thread.sleep(3000);

		final Timeline tl = cfg.buildTimeline();
		System.out.println("Rendering " + tl.totalFrames() + " frames to " + cfg.moviePath);

		MovieRenderer.recordMovie(
				bdv.getBdvHandle().getViewerPanel(),
				cfg.screenWidth,
				cfg.screenHeight,
				tl.transforms,
				tl.frames,
				tl.accel,
				tl.firstTransformIndex,
				cfg.moviePath);
	}
}
