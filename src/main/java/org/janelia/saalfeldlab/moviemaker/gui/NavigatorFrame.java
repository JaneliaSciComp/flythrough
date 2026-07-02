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
