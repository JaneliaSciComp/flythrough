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
package org.janelia.saalfeldlab.moviemaker.gui;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.moviemaker.MovieConfig;
import org.janelia.saalfeldlab.moviemaker.MovieConfig.Segment;
import org.janelia.saalfeldlab.moviemaker.core.ViewTransforms;
import org.janelia.saalfeldlab.moviemaker.core.ViewTransforms.KeyPoint;

import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;

/**
 * Stage 2: navigate the BigDataViewer window and press <b>T</b> to capture the
 * current view as a key point. Key points accumulate, in order, in the side list.
 */
public class NavigatorFrame extends JFrame {

	private static final long serialVersionUID = 1L;

	private final MovieConfig cfg;
	private final BdvStackSource<?> bdv;
	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	private final JList<String> list = new JList<>(listModel);

	public NavigatorFrame(final MovieConfig cfg, final BdvStackSource<?> bdv) {
		super("Movie Maker – Navigate (press T to capture)");
		this.cfg = cfg;
		this.bdv = bdv;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		buildUi();
		installCaptureBehaviour();
		pack();
		setLocationRelativeTo(null);
	}

	private void buildUi() {
		final JLabel help = new JLabel("<html>Pan / zoom / scroll in the viewer.<br>Press <b>T</b> to capture a key point.</html>");
		help.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		list.setVisibleRowCount(14);

		final JButton undo = new JButton("Undo last");
		undo.addActionListener(e -> undoLast());
		final JButton done = new JButton("Done → Verify");
		done.addActionListener(e -> onDone());

		final JPanel south = new JPanel();
		south.add(undo);
		south.add(done);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(help, BorderLayout.NORTH);
		getContentPane().add(new JScrollPane(list), BorderLayout.CENTER);
		getContentPane().add(south, BorderLayout.SOUTH);

		// reflect any pre-existing key points (e.g. re-entering navigate)
		for (int i = 0; i < cfg.keyPoints.size(); ++i)
			listModel.addElement(describe(i, cfg.keyPoints.get(i)));
	}

	private void installCaptureBehaviour() {
		final ViewerPanel vp = bdv.getBdvHandle().getViewerPanel();
		final Behaviours behaviours = new Behaviours(new InputTriggerConfig());
		behaviours.install(bdv.getBdvHandle().getTriggerbindings(), "movie-maker-capture");
		behaviours.behaviour((ClickBehaviour) (x, y) -> {
			final KeyPoint kp = ViewTransforms.capture(vp);
			SwingUtilities.invokeLater(() -> {
				cfg.keyPoints.add(kp);
				cfg.segments.add(new Segment(120, 0));
				listModel.addElement(describe(cfg.keyPoints.size() - 1, kp));
				System.out.println("captured " + kp);
			});
		}, "movie-maker-capture", "T");
	}

	private static String describe(final int index, final KeyPoint kp) {
		final double[] c = kp.displayCenter();
		return String.format("%2d:  (%.0f, %.0f, %.0f)  zoom %.5f", index, c[0], c[1], c[2], kp.displayScale());
	}

	private void undoLast() {
		if (cfg.keyPoints.isEmpty())
			return;
		cfg.keyPoints.remove(cfg.keyPoints.size() - 1);
		if (!cfg.segments.isEmpty())
			cfg.segments.remove(cfg.segments.size() - 1);
		listModel.remove(listModel.size() - 1);
	}

	private void onDone() {
		if (cfg.keyPoints.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Capture at least one key point first (press T).", "No key points", JOptionPane.WARNING_MESSAGE);
			return;
		}
		cfg.syncSegments();
		new VerifyFrame(cfg, bdv).setVisible(true);
		setVisible(false);
		dispose();
	}
}
