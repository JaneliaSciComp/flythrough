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

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
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
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Stage 1b: tune the intensity range / normalization while previewing the data.
 * <b>Reload</b> rebuilds the viewer with the current values (the current view is
 * preserved); <b>OK</b> hands the viewer on to keyframe capture (Stage 2).
 */
public class IntensityFrame extends JFrame {

	private static final long serialVersionUID = 1L;

	private final MovieConfig cfg;
	private BdvStackSource<?> bdv;

	private final JComboBox<Normalization> normalization = new JComboBox<>(Normalization.values());
	private final JTextField claheSlope = new JTextField(8);
	private final JTextField histogramMin = new JTextField(8);
	private final JTextField histogramMax = new JTextField(8);
	private final JComboBox<String> invert = new JComboBox<>(new String[]{"no", "yes"});

	private final JButton reloadButton = new JButton("Reload preview");
	private final JButton okButton = new JButton("OK → Navigate");

	/** Field values the currently displayed preview was built from. */
	private String loadedSig;

	public IntensityFrame(final MovieConfig cfg, final BdvStackSource<?> bdv) {
		super("Movie Maker – Intensity / CLAHE");
		this.cfg = cfg;
		this.bdv = bdv;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		normalization.setSelectedItem(cfg.normalizationEnum());
		claheSlope.setText(Float.toString(cfg.claheSlope));
		histogramMin.setText(Integer.toString(cfg.histogramMin));
		histogramMax.setText(Integer.toString(cfg.histogramMax));
		invert.setSelectedItem(cfg.invert ? "yes" : "no");
		loadedSig = sig();
		buildUi();
		pack();
		setLocationRelativeTo(null);
	}

	private void buildUi() {
		final JLabel help = new JLabel("<html>Adjust the values, press <b>Reload preview</b> to see them,<br>"
				+ "then <b>OK</b> to move on to key point capture.</html>");
		help.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		final JPanel form = new JPanel(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 5, 3, 5);
		c.anchor = GridBagConstraints.WEST;
		int row = 0;
		row = SetupPanel.addRow(form, c, row, "Normalization:", normalization);
		row = SetupPanel.addRow(form, c, row, "CLAHE slope:", claheSlope);
		row = SetupPanel.addRow(form, c, row, "Histogram clip min (16-bit):", histogramMin);
		row = SetupPanel.addRow(form, c, row, "Histogram clip max (16-bit):", histogramMax);
		row = SetupPanel.addRow(form, c, row, "Invert intensities:", invert);

		reloadButton.addActionListener(e -> onReload());
		okButton.addActionListener(e -> onOk());

		final JPanel south = new JPanel();
		south.add(reloadButton);
		south.add(okButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(help, BorderLayout.NORTH);
		getContentPane().add(form, BorderLayout.CENTER);
		getContentPane().add(south, BorderLayout.SOUTH);
	}

	/** The field values, as a single string, to detect edits since the last preview. */
	private String sig() {
		return normalization.getSelectedItem() + "|" + claheSlope.getText().trim() + "|"
				+ histogramMin.getText().trim() + "|" + histogramMax.getText().trim() + "|" + invert.getSelectedItem();
	}

	/** Push the fields into the config. */
	private void apply() {
		cfg.normalization = ((Normalization) normalization.getSelectedItem()).name();
		cfg.claheSlope = Float.parseFloat(claheSlope.getText().trim());
		cfg.histogramMin = Integer.parseInt(histogramMin.getText().trim());
		cfg.histogramMax = Integer.parseInt(histogramMax.getText().trim());
		cfg.invert = "yes".equals(invert.getSelectedItem());
		if (cfg.histogramMin >= cfg.histogramMax)
			throw new NumberFormatException("clip min must be < clip max");
	}

	private void onReload() {
		try {
			apply();
		} catch (final NumberFormatException ex) {
			JOptionPane.showMessageDialog(this, "Please check numeric fields: " + ex.getMessage(), "Invalid input", JOptionPane.ERROR_MESSAGE);
			return;
		}

		// keep the current view across the reload
		final AffineTransform3D view = new AffineTransform3D();
		bdv.getBdvHandle().getViewerPanel().state().getViewerTransform(view);
		final BdvStackSource<?> old = bdv;

		reloadButton.setEnabled(false);
		okButton.setEnabled(false);
		reloadButton.setText("Reloading…");

		new SwingWorker<BdvStackSource<?>, Void>() {
			@Override
			protected BdvStackSource<?> doInBackground() throws Exception {
				return MovieViewer.show(cfg, true);
			}

			@Override
			protected void done() {
				reloadButton.setEnabled(true);
				okButton.setEnabled(true);
				reloadButton.setText("Reload preview");
				try {
					bdv = get();
					bdv.getBdvHandle().getViewerPanel().state().setViewerTransform(view);
					old.getBdvHandle().close();
					loadedSig = sig();
				} catch (final Exception ex) {
					ex.printStackTrace();
					JOptionPane.showMessageDialog(IntensityFrame.this, "Reload failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
					bdv = old;
				}
			}
		}.execute();
	}

	private void onOk() {
		if (!sig().equals(loadedSig)) {
			JOptionPane.showMessageDialog(this,
					"Values changed since the last preview – reloading.\nPress OK again once it looks right.",
					"Not previewed yet", JOptionPane.INFORMATION_MESSAGE);
			onReload();
			return;
		}
		new NavigatorFrame(cfg, bdv).setVisible(true);
		setVisible(false);
		dispose();
	}
}
