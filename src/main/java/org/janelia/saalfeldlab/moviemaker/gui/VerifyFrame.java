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
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;

import org.janelia.saalfeldlab.moviemaker.MovieConfig;
import org.janelia.saalfeldlab.moviemaker.MovieConfig.Segment;
import org.janelia.saalfeldlab.moviemaker.MovieMaker;
import org.janelia.saalfeldlab.moviemaker.core.MovieRenderer;
import org.janelia.saalfeldlab.moviemaker.core.ViewTransforms.KeyPoint;

import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;

/**
 * Stage 3: verify and time the movie. Each key point gets a thumbnail; the
 * frame count and acceleration of the motion <em>into</em> each key point are
 * editable, the first key point can be held, and the movie can return to the
 * first key point at the end. Thumbnails can be dragged to reorder key points.
 */
public class VerifyFrame extends JFrame {

	private static final long serialVersionUID = 1L;

	/** A key point bundled with its incoming-segment timing and thumbnail. */
	private static class Row {
		final KeyPoint kp;
		int frames;
		int accel;
		BufferedImage thumb;

		Row(final KeyPoint kp, final Segment seg) {
			this.kp = kp;
			this.frames = seg.frames;
			this.accel = seg.accel;
		}
	}

	private final MovieConfig cfg;
	private final BdvStackSource<?> bdv;

	private final DefaultListModel<Row> model = new DefaultListModel<>();
	private final JList<Row> list = new JList<>(model);

	private final int thumbWidth = 220;
	private final int thumbHeight;

	// per-segment editors (bound to the selected row)
	private final JSpinner segFrames = new JSpinner(new SpinnerNumberModel(120, 0, 100000, 10));
	private final JComboBox<String> segAccel = new JComboBox<>(MovieRenderer.ACCEL_NAMES);

	// hold / return editors
	private final JCheckBox holdEnabled = new JCheckBox("Hold on first key point");
	private final JSpinner holdFrames = new JSpinner(new SpinnerNumberModel(120, 0, 100000, 10));
	private final JCheckBox returnEnabled = new JCheckBox("Return to first key point at end");
	private final JSpinner returnFrames = new JSpinner(new SpinnerNumberModel(120, 0, 100000, 10));
	private final JComboBox<String> returnAccel = new JComboBox<>(MovieRenderer.ACCEL_NAMES);

	private final JButton saveButton = new JButton("Save config");
	private final JButton renderButton = new JButton("Render movie now");

	private boolean updatingEditors = false;

	public VerifyFrame(final MovieConfig cfg, final BdvStackSource<?> bdv) {
		super("Movie Maker – Verify");
		this.cfg = cfg;
		this.bdv = bdv;
		this.thumbHeight = Math.round(thumbWidth * (float) cfg.screenHeight / cfg.screenWidth);
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

		cfg.syncSegments();
		for (int i = 0; i < cfg.keyPoints.size(); ++i)
			model.addElement(new Row(cfg.keyPoints.get(i), cfg.segments.get(i)));

		buildUi();
		loadTimingFromConfig();
		pack();
		setLocationRelativeTo(null);
		renderThumbnailsInBackground();
	}

	private void buildUi() {
		list.setCellRenderer(new RowRenderer());
		list.setFixedCellHeight(thumbHeight + 12);
		list.setDragEnabled(true);
		list.setDropMode(DropMode.INSERT);
		list.setTransferHandler(new RowReorderHandler());
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting())
				pushSelectionToEditors();
		});

		segFrames.addChangeListener(e -> pullSegmentEditors());
		segAccel.addActionListener(e -> pullSegmentEditors());

		final JPanel segEditor = new JPanel(new FlowLayout(FlowLayout.LEFT));
		segEditor.setBorder(BorderFactory.createTitledBorder("Motion INTO selected key point"));
		segEditor.add(new JLabel("frames:"));
		segEditor.add(segFrames);
		segEditor.add(new JLabel("acceleration:"));
		segEditor.add(segAccel);

		final JPanel holdEditor = new JPanel(new FlowLayout(FlowLayout.LEFT));
		holdEditor.setBorder(BorderFactory.createTitledBorder("Start"));
		holdEditor.add(holdEnabled);
		holdEditor.add(new JLabel("frames:"));
		holdEditor.add(holdFrames);

		final JPanel returnEditor = new JPanel(new FlowLayout(FlowLayout.LEFT));
		returnEditor.setBorder(BorderFactory.createTitledBorder("End"));
		returnEditor.add(returnEnabled);
		returnEditor.add(new JLabel("frames:"));
		returnEditor.add(returnFrames);
		returnEditor.add(new JLabel("acceleration:"));
		returnEditor.add(returnAccel);

		final JPanel editors = new JPanel();
		editors.setLayout(new javax.swing.BoxLayout(editors, javax.swing.BoxLayout.Y_AXIS));
		editors.add(holdEditor);
		editors.add(segEditor);
		editors.add(returnEditor);
		editors.add(Box.createVerticalGlue());

		saveButton.addActionListener(e -> onSave());
		renderButton.addActionListener(e -> onRender());
		final JPanel south = new JPanel();
		south.add(saveButton);
		south.add(renderButton);

		final JScrollPane listScroll = new JScrollPane(list);
		listScroll.setBorder(BorderFactory.createTitledBorder("Key points (drag to reorder)"));

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(listScroll, BorderLayout.CENTER);
		getContentPane().add(editors, BorderLayout.EAST);
		getContentPane().add(south, BorderLayout.SOUTH);

		if (!model.isEmpty())
			list.setSelectedIndex(0);
	}

	// ---- timing editors ----

	private void loadTimingFromConfig() {
		holdEnabled.setSelected(cfg.holdFirstFrames > 0);
		holdFrames.setValue(Math.max(0, cfg.holdFirstFrames));
		returnEnabled.setSelected(cfg.returnToFirst);
		returnFrames.setValue(Math.max(0, cfg.returnFrames));
		returnAccel.setSelectedIndex(clampAccelIndex(cfg.returnAccel));
	}

	private void pushSelectionToEditors() {
		final Row r = list.getSelectedValue();
		if (r == null)
			return;
		updatingEditors = true;
		segFrames.setValue(r.frames);
		segAccel.setSelectedIndex(clampAccelIndex(r.accel));
		updatingEditors = false;
	}

	private void pullSegmentEditors() {
		if (updatingEditors)
			return;
		final Row r = list.getSelectedValue();
		if (r == null)
			return;
		r.frames = (Integer) segFrames.getValue();
		r.accel = segAccel.getSelectedIndex();
		list.repaint();
	}

	private static int clampAccelIndex(final int accel) {
		return Math.max(0, Math.min(MovieRenderer.ACCEL_NAMES.length - 1, accel));
	}

	// ---- write back to config ----

	private void writeBack() {
		final List<KeyPoint> kps = new ArrayList<>();
		final List<Segment> segs = new ArrayList<>();
		for (int i = 0; i < model.size(); ++i) {
			final Row r = model.get(i);
			kps.add(r.kp);
			segs.add(new Segment(r.frames, r.accel));
		}
		cfg.keyPoints = kps;
		cfg.segments = segs;
		cfg.holdFirstFrames = holdEnabled.isSelected() ? (Integer) holdFrames.getValue() : 0;
		cfg.returnToFirst = returnEnabled.isSelected();
		cfg.returnFrames = (Integer) returnFrames.getValue();
		cfg.returnAccel = returnAccel.getSelectedIndex();
	}

	private File configFile() {
		return new File(cfg.moviePath, "movie-config.json");
	}

	private void onSave() {
		writeBack();
		try {
			final File f = configFile();
			cfg.save(f);
			JOptionPane.showMessageDialog(this, "Saved config to\n" + f.getAbsolutePath(), "Saved", JOptionPane.INFORMATION_MESSAGE);
		} catch (final Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to save:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void onRender() {
		writeBack();
		final File f = configFile();
		try {
			cfg.save(f);
		} catch (final Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to save config:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		renderButton.setEnabled(false);
		saveButton.setEnabled(false);
		renderButton.setText("Rendering…");

		new SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() throws Exception {
				MovieMaker.render(cfg);
				return null;
			}

			@Override
			protected void done() {
				renderButton.setEnabled(true);
				saveButton.setEnabled(true);
				renderButton.setText("Render movie now");
				try {
					get();
					JOptionPane.showMessageDialog(VerifyFrame.this,
							"Rendered PNG frames to\n" + cfg.moviePath,
							"Done", JOptionPane.INFORMATION_MESSAGE);
				} catch (final Exception ex) {
					ex.printStackTrace();
					JOptionPane.showMessageDialog(VerifyFrame.this, "Render failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	// ---- thumbnails ----

	private void renderThumbnailsInBackground() {
		final ViewerPanel vp = bdv.getBdvHandle().getViewerPanel();
		new SwingWorker<Void, Integer>() {
			@Override
			protected Void doInBackground() {
				for (int i = 0; i < model.size(); ++i) {
					final Row r = model.get(i);
					try {
						final BufferedImage full = MovieRenderer.renderSingleFrame(
								vp, cfg.screenWidth, cfg.screenHeight, r.kp.toTransform());
						r.thumb = scale(full, thumbWidth, thumbHeight);
					} catch (final Exception ex) {
						ex.printStackTrace();
					}
					publish(i);
				}
				return null;
			}

			@Override
			protected void process(final List<Integer> chunks) {
				list.repaint();
			}
		}.execute();
	}

	private static BufferedImage scale(final BufferedImage src, final int w, final int h) {
		final BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Image scaled = src.getScaledInstance(w, h, Image.SCALE_SMOOTH);
		final java.awt.Graphics2D g = dst.createGraphics();
		g.drawImage(scaled, 0, 0, null);
		g.dispose();
		return dst;
	}

	private class RowRenderer extends DefaultListCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getListCellRendererComponent(final JList<?> l, final Object value, final int index,
				final boolean isSelected, final boolean cellHasFocus) {
			super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
			final Row r = (Row) value;
			setIcon(r.thumb != null ? new ImageIcon(r.thumb) : null);
			final String motion = index == 0
					? "start"
					: r.frames + " f, " + MovieRenderer.ACCEL_NAMES[clampAccelIndex(r.accel)];
			setText(String.format("<html>#%d &nbsp; (%.0f, %.0f, %.0f) zoom %.5f<br>into: %s</html>",
					index, r.kp.wx, r.kp.wy, r.kp.wz, r.kp.scale, motion));
			setVerticalTextPosition(BOTTOM);
			setHorizontalTextPosition(RIGHT);
			return this;
		}
	}

	/** Reorder rows within the list by drag and drop. */
	private class RowReorderHandler extends TransferHandler {
		private static final long serialVersionUID = 1L;
		private int fromIndex = -1;

		@Override
		public int getSourceActions(final JComponent c) {
			return MOVE;
		}

		@Override
		protected Transferable createTransferable(final JComponent c) {
			fromIndex = list.getSelectedIndex();
			return new StringSelection("row");
		}

		@Override
		public boolean canImport(final TransferSupport support) {
			return support.isDrop();
		}

		@Override
		public boolean importData(final TransferSupport support) {
			if (!support.isDrop() || fromIndex < 0)
				return false;
			final JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
			int to = dl.getIndex();
			if (to > fromIndex)
				to--;
			if (to == fromIndex || to < 0 || to > model.size() - 1) {
				fromIndex = -1;
				return false;
			}
			final Row r = model.remove(fromIndex);
			model.add(to, r);
			list.setSelectedIndex(to);
			fromIndex = -1;
			SwingUtilities.invokeLater(list::repaint);
			return true;
		}
	}
}
