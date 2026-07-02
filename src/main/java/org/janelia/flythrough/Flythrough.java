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
package org.janelia.flythrough;

import java.io.File;
import java.util.concurrent.Callable;

import javax.swing.SwingUtilities;

import org.janelia.flythrough.MovieConfig.Timeline;
import org.janelia.flythrough.core.MovieRenderer;
import org.janelia.flythrough.core.MovieViewer;
import org.janelia.flythrough.gui.SetupPanel;

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
@Command(name = "flythrough", mixinStandardHelpOptions = true,
		description = "Author and render fly-through movies of 3D microscopy volumes (N5 / OME-Zarr).")
public class Flythrough implements Callable<Void> {

	@Option(names = "--config", description = "Render this movie config JSON headlessly (no GUI).")
	private File config;

	public static void main(final String... args) {
		new CommandLine(new Flythrough()).execute(args);
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
