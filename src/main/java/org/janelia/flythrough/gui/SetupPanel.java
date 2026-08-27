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
package org.janelia.flythrough.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import org.janelia.flythrough.MovieConfig;
import org.janelia.flythrough.core.MovieViewer;
import org.janelia.flythrough.core.Normalization;

import bdv.util.BdvStackSource;

/**
 * Stage 1: collect the dataset and display parameters, then open the data in a
 * BigDataViewer window for keyframe capture (Stage 2).
 */
public class SetupPanel extends JFrame {

	private static final long serialVersionUID = 1L;

	private final JTextField dataPath = new JTextField(36);
	private final JTextField dataGroup = new JTextField("", 36);
	private final JComboBox<String> scalePrefix = new JComboBox<>(new String[]{"", "s"});
	private final JTextField moviePath = new JTextField(36);
	private final JTextField expansionFactor = new JTextField("1.0", 8);
	private final JComboBox<Normalization> normalization = new JComboBox<>(Normalization.values());
	private final JTextField claheSlope = new JTextField("1.5", 8);
	private final JTextField histogramMin = new JTextField("0", 8);
	private final JTextField histogramMax = new JTextField("65535", 8);
	private final JComboBox<String> invert = new JComboBox<>(new String[]{"no", "yes"});
	private final JTextField screenWidth = new JTextField("1050", 8);
	private final JTextField screenHeight = new JTextField("750", 8);
	private final JTextField fps = new JTextField("30", 8);

	private final JButton startButton = new JButton("Start → Intensity");

	public SetupPanel() {
		super("Movie Maker – Setup");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		normalization.setSelectedItem(Normalization.CLAHE);
		buildUi();
		pack();
		setLocationRelativeTo(null);
	}

	private void buildUi() {
		final JPanel form = new JPanel(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 5, 3, 5);
		c.anchor = GridBagConstraints.WEST;
		int row = 0;

		row = addRow(form, c, row, "Data path (N5 / OME-Zarr):", withBrowse(dataPath, true));
		row = addRow(form, c, row, "Data group (blank for root):", dataGroup);
		row = addRow(form, c, row, "Scale prefix (\"\"=OME-Zarr, \"s\"=N5):", scalePrefix);
		row = addRow(form, c, row, "Movie output directory:", withBrowse(moviePath, false));
		row = addRow(form, c, row, "Expansion factor:", expansionFactor);
		row = addRow(form, c, row, "Normalization:", normalization);
		row = addRow(form, c, row, "CLAHE slope:", claheSlope);
		row = addRow(form, c, row, "Histogram clip min (16-bit):", histogramMin);
		row = addRow(form, c, row, "Histogram clip max (16-bit):", histogramMax);
		row = addRow(form, c, row, "Invert intensities:", invert);
		row = addRow(form, c, row, "Screen width:", screenWidth);
		row = addRow(form, c, row, "Screen height:", screenHeight);
		row = addRow(form, c, row, "FPS (for playback):", fps);

		startButton.addActionListener(e -> onStart());

		final JPanel south = new JPanel();
		south.add(startButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(form, BorderLayout.CENTER);
		getContentPane().add(south, BorderLayout.SOUTH);
	}

	private JPanel withBrowse(final JTextField field, final boolean filesAndDirs) {
		final JPanel p = new JPanel(new BorderLayout(4, 0));
		p.add(field, BorderLayout.CENTER);
		final JButton browse = new JButton("…");
		browse.addActionListener(e -> {
			final JFileChooser chooser = new JFileChooser();
			chooser.setFileSelectionMode(filesAndDirs ? JFileChooser.FILES_AND_DIRECTORIES : JFileChooser.DIRECTORIES_ONLY);
			if (!field.getText().isEmpty())
				chooser.setSelectedFile(new File(field.getText()));
			if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
				field.setText(chooser.getSelectedFile().getAbsolutePath());
		});
		p.add(browse, BorderLayout.EAST);
		return p;
	}

	static int addRow(final JPanel form, final GridBagConstraints c, final int row, final String label, final java.awt.Component field) {
		c.gridx = 0;
		c.gridy = row;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(new JLabel(label), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(field, c);
		return row + 1;
	}

	private MovieConfig readConfig() {
		final MovieConfig cfg = new MovieConfig();
		cfg.dataPath = dataPath.getText().trim();
		cfg.dataGroup = dataGroup.getText().trim();
		cfg.scalePrefix = (String) scalePrefix.getSelectedItem();
		cfg.moviePath = moviePath.getText().trim();
		cfg.expansionFactor = Double.parseDouble(expansionFactor.getText().trim());
		cfg.normalization = ((Normalization) normalization.getSelectedItem()).name();
		cfg.claheSlope = Float.parseFloat(claheSlope.getText().trim());
		cfg.histogramMin = Integer.parseInt(histogramMin.getText().trim());
		cfg.histogramMax = Integer.parseInt(histogramMax.getText().trim());
		cfg.invert = "yes".equals(invert.getSelectedItem());
		cfg.screenWidth = Integer.parseInt(screenWidth.getText().trim());
		cfg.screenHeight = Integer.parseInt(screenHeight.getText().trim());
		cfg.fps = Integer.parseInt(fps.getText().trim());
		return cfg;
	}

	private void onStart() {
		final MovieConfig cfg;
		try {
			cfg = readConfig();
		} catch (final NumberFormatException ex) {
			JOptionPane.showMessageDialog(this, "Please check numeric fields: " + ex.getMessage(), "Invalid input", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (cfg.dataPath.isEmpty() || cfg.moviePath.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Data path and movie output directory are required.", "Missing input", JOptionPane.ERROR_MESSAGE);
			return;
		}

		startButton.setEnabled(false);
		startButton.setText("Loading data…");

		new SwingWorker<BdvStackSource<?>, Void>() {
			@Override
			protected BdvStackSource<?> doInBackground() throws Exception {
				return MovieViewer.show(cfg, true);
			}

			@Override
			protected void done() {
				try {
					final BdvStackSource<?> bdv = get();
					new IntensityFrame(cfg, bdv).setVisible(true);
					setVisible(false);
					dispose();
				} catch (final Exception ex) {
					ex.printStackTrace();
					JOptionPane.showMessageDialog(SetupPanel.this, "Failed to open data:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
					startButton.setEnabled(true);
					startButton.setText("Start → Intensity");
				}
			}
		}.execute();
	}
}
