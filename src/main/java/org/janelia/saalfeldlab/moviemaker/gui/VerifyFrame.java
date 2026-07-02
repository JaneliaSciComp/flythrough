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
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.janelia.saalfeldlab.moviemaker.MovieConfig;
import org.janelia.saalfeldlab.moviemaker.MovieConfig.Segment;
import org.janelia.saalfeldlab.moviemaker.MovieMaker;
import org.janelia.saalfeldlab.moviemaker.core.MovieRenderer;
import org.janelia.saalfeldlab.moviemaker.core.MovieViewer;
import org.janelia.saalfeldlab.moviemaker.core.ViewTransforms.KeyPoint;

import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;

/**
 * Stage 3: verify and time the movie. Each key point row shows a thumbnail and
 * its own editable frame count + acceleration (the motion INTO that key point),
 * so segments can be sped up or slowed down individually. The first key point
 * can be held; the movie can return to it at the end. Rows can be dragged (or
 * moved with the arrow buttons) to reorder key points.
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

	private final List<Row> rows = new ArrayList<>();
	private final JPanel rowsPanel = new JPanel();

	private final int thumbWidth = 220;
	private final int thumbHeight;

	// hold / return editors
	private final JCheckBox holdEnabled = new JCheckBox("Hold on first key point");
	private final JSpinner holdFrames = new JSpinner(new SpinnerNumberModel(120, 0, 100000, 10));
	private final JCheckBox returnEnabled = new JCheckBox("Return to first key point at end");
	private final JSpinner returnFrames = new JSpinner(new SpinnerNumberModel(120, 0, 100000, 10));
	private final JComboBox<String> returnAccel = new JComboBox<>(MovieRenderer.ACCEL_NAMES);

	private final JButton saveButton = new JButton("Save config");
	private final JButton renderButton = new JButton("Render movie now");

	private int dragFrom = -1;

	public VerifyFrame(final MovieConfig cfg, final BdvStackSource<?> bdv) {
		super("Movie Maker – Verify");
		this.cfg = cfg;
		this.bdv = bdv;
		this.thumbHeight = Math.round(thumbWidth * (float) cfg.screenHeight / cfg.screenWidth);
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

		cfg.syncSegments();
		for (int i = 0; i < cfg.keyPoints.size(); ++i)
			rows.add(new Row(cfg.keyPoints.get(i), cfg.segments.get(i)));

		buildUi();
		loadTimingFromConfig();
		rebuildRows();
		pack();
		setSize(Math.max(getWidth(), 520), Math.min(900, thumbHeight * Math.min(rows.size(), 4) + 260));
		setLocationRelativeTo(null);
		renderThumbnailsInBackground();
	}

	private void buildUi() {
		rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));

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

		final JScrollPane listScroll = new JScrollPane(rowsPanel);
		listScroll.setBorder(BorderFactory.createTitledBorder("Key points (drag a thumbnail or use ▲▼ to reorder)"));
		listScroll.getVerticalScrollBar().setUnitIncrement(16);

		saveButton.addActionListener(e -> onSave());
		renderButton.addActionListener(e -> onRender());
		final JPanel south = new JPanel();
		south.add(saveButton);
		south.add(renderButton);

		// return editor + action buttons stacked at the bottom
		final JPanel southStack = new JPanel();
		southStack.setLayout(new BoxLayout(southStack, BoxLayout.Y_AXIS));
		southStack.add(returnEditor);
		southStack.add(south);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(holdEditor, BorderLayout.NORTH);
		getContentPane().add(listScroll, BorderLayout.CENTER);
		getContentPane().add(southStack, BorderLayout.SOUTH);
	}

	// ---- row panels ----

	private void rebuildRows() {
		rowsPanel.removeAll();
		for (int i = 0; i < rows.size(); ++i)
			rowsPanel.add(new RowPanel(rows.get(i), i));
		rowsPanel.revalidate();
		rowsPanel.repaint();
	}

	private class RowPanel extends JPanel {
		private static final long serialVersionUID = 1L;
		final Row row;
		final int position;

		RowPanel(final Row row, final int position) {
			this.row = row;
			this.position = position;
			setLayout(new BorderLayout(8, 0));
			setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
					BorderFactory.createEmptyBorder(4, 4, 4, 4)));
			setAlignmentX(LEFT_ALIGNMENT);
			final int h = thumbHeight + 8;
			setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

			// thumbnail (drag handle)
			final JLabel thumb = new JLabel();
			thumb.setPreferredSize(new Dimension(thumbWidth, thumbHeight));
			thumb.setHorizontalAlignment(JLabel.CENTER);
			thumb.setOpaque(true);
			thumb.setBackground(Color.DARK_GRAY);
			thumb.setToolTipText("Drag to reorder");
			thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			if (row.thumb != null)
				thumb.setIcon(new ImageIcon(row.thumb));
			else
				thumb.setText("rendering…");
			installDrag(thumb);
			add(thumb, BorderLayout.WEST);

			// controls
			final JPanel controls = new JPanel();
			controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

			final double[] c = row.kp.displayCenter();
			final JLabel title = new JLabel(String.format("#%d  (%.0f, %.0f, %.0f)  zoom %.5f",
					position, c[0], c[1], c[2], row.kp.displayScale()));
			title.setAlignmentX(LEFT_ALIGNMENT);
			controls.add(title);

			final JPanel timing = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
			timing.setAlignmentX(LEFT_ALIGNMENT);
			if (position == 0) {
				timing.add(new JLabel("start of movie (use \"Hold\" above to pause here)"));
			} else {
				final JSpinner frames = new JSpinner(new SpinnerNumberModel(Math.max(0, row.frames), 0, 100000, 10));
				frames.addChangeListener(e -> row.frames = (Integer) frames.getValue());
				final JComboBox<String> accel = new JComboBox<>(MovieRenderer.ACCEL_NAMES);
				accel.setSelectedIndex(clampAccelIndex(row.accel));
				accel.addActionListener(e -> row.accel = accel.getSelectedIndex());
				timing.add(new JLabel("frames in:"));
				timing.add(frames);
				timing.add(new JLabel("accel:"));
				timing.add(accel);
			}
			controls.add(timing);

			final JPanel move = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
			move.setAlignmentX(LEFT_ALIGNMENT);
			final JButton up = new JButton("▲");
			up.setEnabled(position > 0);
			up.addActionListener(e -> moveRow(position, position - 1));
			final JButton down = new JButton("▼");
			down.setEnabled(position < rows.size() - 1);
			down.addActionListener(e -> moveRow(position, position + 1));
			move.add(up);
			move.add(down);
			controls.add(move);

			add(controls, BorderLayout.CENTER);
		}

		private void installDrag(final JComponent handle) {
			final MouseAdapter ma = new MouseAdapter() {
				@Override
				public void mousePressed(final MouseEvent e) {
					dragFrom = position;
				}

				@Override
				public void mouseReleased(final MouseEvent e) {
					if (dragFrom < 0)
						return;
					final Point p = SwingUtilities.convertPoint(handle, e.getPoint(), rowsPanel);
					final int to = rowIndexAt(p);
					moveRow(dragFrom, to);
					dragFrom = -1;
				}
			};
			handle.addMouseListener(ma);
			handle.addMouseMotionListener(ma);
		}
	}

	/** Which row position contains point p (in rowsPanel coordinates); clamps to ends. */
	private int rowIndexAt(final Point p) {
		Component c = SwingUtilities.getDeepestComponentAt(rowsPanel, p.x, p.y);
		while (c != null && !(c instanceof RowPanel))
			c = c.getParent();
		if (c instanceof RowPanel)
			return ((RowPanel) c).position;
		// past the last row -> drop at the end
		if (p.y >= rowsPanel.getHeight() - 1)
			return rows.size() - 1;
		return dragFrom;
	}

	private void moveRow(final int from, int to) {
		if (from < 0 || from >= rows.size())
			return;
		to = Math.max(0, Math.min(rows.size() - 1, to));
		if (from == to)
			return;
		final Row r = rows.remove(from);
		rows.add(to, r);
		rebuildRows();
	}

	private static int clampAccelIndex(final int accel) {
		return Math.max(0, Math.min(MovieRenderer.ACCEL_NAMES.length - 1, accel));
	}

	// ---- timing config ----

	private void loadTimingFromConfig() {
		holdEnabled.setSelected(cfg.holdFirstFrames > 0);
		holdFrames.setValue(Math.max(0, cfg.holdFirstFrames));
		returnEnabled.setSelected(cfg.returnToFirst);
		returnFrames.setValue(Math.max(0, cfg.returnFrames));
		returnAccel.setSelectedIndex(clampAccelIndex(cfg.returnAccel));
	}

	private void writeBack() {
		final List<KeyPoint> kps = new ArrayList<>();
		final List<Segment> segs = new ArrayList<>();
		for (final Row r : rows) {
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
		new SwingWorker<Void, Row>() {
			@Override
			protected Void doInBackground() throws Exception {
				// Render thumbnails from a dedicated NON-volatile viewer: paint() then
				// blocks until each frame is fully and correctly rendered, so thumbnails
				// are never captured black or lagging the previous transform (as they do
				// on the asynchronous volatile navigation viewer).
				BdvStackSource<?> thumbBdv = null;
				try {
					thumbBdv = MovieViewer.show(cfg, false);
					final BdvStackSource<?> handle = thumbBdv;
					final Window w = SwingUtilities.getWindowAncestor(handle.getBdvHandle().getViewerPanel());
					SwingUtilities.invokeLater(() -> {
						if (w != null)
							w.setVisible(false);
					});
					final ViewerPanel vp = thumbBdv.getBdvHandle().getViewerPanel();
					Thread.sleep(500);

					for (final Row r : rows) {
						try {
							final BufferedImage full = MovieRenderer.renderSingleFrame(
									vp, cfg.screenWidth, cfg.screenHeight, r.kp.toTransform());
							r.thumb = scale(full, thumbWidth, thumbHeight);
						} catch (final Exception ex) {
							ex.printStackTrace();
						}
						publish(r);
					}
				} finally {
					if (thumbBdv != null) {
						final BdvStackSource<?> handle = thumbBdv;
						SwingUtilities.invokeLater(() -> handle.getBdvHandle().close());
					}
				}
				return null;
			}

			@Override
			protected void process(final List<Row> chunks) {
				rebuildRows();
			}

			@Override
			protected void done() {
				rebuildRows();
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
}
