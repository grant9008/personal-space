package com.grant9008.personalspace;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The Personal Space sidebar: live status, every setting, and a copyable diagnostics report.
 *
 * <p>Swing thread only. The plugin pushes a fresh {@link Snapshot} a couple of times a second via
 * {@link #update}, and calls {@link #refreshControls} whenever the config changes (including
 * changes made from RuneLite's own settings screen). Controls write straight to the config, so
 * the sidebar and the settings screen always agree.
 */
final class PersonalSpacePanel extends PluginPanel
{
	private static final int TEXT_WIDTH_PX = 135;

	private final ConfigManager configManager;
	private final PersonalSpaceConfig config;

	private final JLabel headlineDot = new JLabel();
	private final JLabel headlineTitle = new JLabel();
	private final JLabel headlineDetail = new JLabel();

	private final JCheckBox activeBox = new JCheckBox("Effect on");
	private final JComboBox<PersonalSpaceConfig.Mode> modeBox = new JComboBox<>(PersonalSpaceConfig.Mode.values());
	private final JComboBox<PersonalSpaceConfig.Separation> separationBox = new JComboBox<>(PersonalSpaceConfig.Separation.values());
	private final JSpinner maxStackSpinner = new JSpinner(new SpinnerNumberModel(
		PersonalSpaceConfig.MAX_STACK, PersonalSpaceConfig.MIN_STACK, PersonalSpaceConfig.MAX_STACK, 1));
	private final JCheckBox includeLocalBox = new JCheckBox("Move my character too");
	private final JCheckBox smoothingBox = new JCheckBox("Smooth movement");
	private final JSlider testOffsetSlider = new JSlider(PersonalSpaceConfig.MIN_TEST_OFFSET, PersonalSpaceConfig.MAX_TEST_OFFSET, 32);
	private final JLabel testOffsetValue = new JLabel();

	private final JPanel checksPanel = new JPanel(new GridBagLayout());
	private final List<JLabel[]> checkRows = new ArrayList<>();

	private final JButton copyButton = new JButton("Copy report");
	private final Timer copyReset = new Timer(2500, e -> copyButton.setText("Copy report"));

	/** True while controls are being set from the config, so their listeners don't write it back. */
	private boolean updatingControls;
	private Snapshot last = new Snapshot();

	PersonalSpacePanel(ConfigManager configManager, PersonalSpaceConfig config)
	{
		this.configManager = configManager;
		this.config = config;

		setLayout(new GridBagLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTH;
		c.insets = new Insets(0, 0, 8, 0);

		JLabel title = new JLabel("Personal Space");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		add(title, c);

		c.gridy++;
		add(buildHeadline(), c);

		c.gridy++;
		add(sectionLabel("Settings"), c);
		c.gridy++;
		add(buildControls(), c);

		c.gridy++;
		add(sectionLabel("Live checks"), c);
		c.gridy++;
		checksPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		checksPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
		add(checksPanel, c);

		c.gridy++;
		copyButton.setToolTipText("Copies everything shown here as text, to paste into a message when something looks wrong.");
		copyButton.setFocusable(false);
		copyButton.addActionListener(e -> copyReport());
		add(copyButton, c);

		c.gridy++;
		add(sectionLabel("How to test"), c);
		c.gridy++;
		add(buildHelp(), c);

		// Push everything to the top when the sidebar is taller than the content.
		c.gridy++;
		c.weighty = 1;
		JPanel filler = new JPanel();
		filler.setOpaque(false);
		add(filler, c);

		wireControls();
		refreshControls();
		update(last);
	}

	// ---- called by the plugin ----------------------------------------------------------

	/** Swing thread. Show a fresh snapshot. */
	void update(Snapshot s)
	{
		last = s;

		StatusSummary.Headline h = StatusSummary.headline(s);
		headlineDot.setIcon(new Dot(colorFor(h.level), 12));
		headlineTitle.setText(wrap(h.title));
		headlineDetail.setText(wrap(h.detail));

		List<StatusSummary.Check> checks = StatusSummary.checks(s);
		if (checks.size() != checkRows.size())
		{
			rebuildCheckRows(checks.size());
		}
		for (int i = 0; i < checks.size(); i++)
		{
			StatusSummary.Check check = checks.get(i);
			JLabel[] row = checkRows.get(i);
			row[0].setIcon(new Dot(colorFor(check.level), 8));
			row[0].setText(check.label);
			row[1].setText(check.value);
		}
	}

	/** Swing thread. Set every control from the current config without writing anything back. */
	void refreshControls()
	{
		updatingControls = true;
		try
		{
			activeBox.setSelected(config.active());
			modeBox.setSelectedItem(config.mode());
			separationBox.setSelectedItem(config.separation());
			maxStackSpinner.setValue(clamp(config.maxStack(), PersonalSpaceConfig.MIN_STACK, PersonalSpaceConfig.MAX_STACK));
			includeLocalBox.setSelected(config.includeLocalPlayer());
			smoothingBox.setSelected(config.smoothing());
			testOffsetSlider.setValue(clamp(config.testOffset(), PersonalSpaceConfig.MIN_TEST_OFFSET, PersonalSpaceConfig.MAX_TEST_OFFSET));
			testOffsetValue.setText(testOffsetSlider.getValue() + " units");
			updateEnabledState();
		}
		finally
		{
			updatingControls = false;
		}
	}

	// ---- building ----------------------------------------------------------------------

	private JPanel buildHeadline()
	{
		JPanel card = new JPanel(new BorderLayout(8, 0));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 8, 8, 8));

		headlineDot.setVerticalAlignment(SwingConstants.TOP);
		headlineDot.setBorder(new EmptyBorder(2, 0, 0, 0));
		card.add(headlineDot, BorderLayout.WEST);

		JPanel text = new JPanel(new BorderLayout(0, 4));
		text.setOpaque(false);
		headlineTitle.setFont(FontManager.getRunescapeBoldFont());
		headlineTitle.setForeground(Color.WHITE);
		headlineDetail.setFont(FontManager.getRunescapeSmallFont());
		headlineDetail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.add(headlineTitle, BorderLayout.NORTH);
		text.add(headlineDetail, BorderLayout.CENTER);
		card.add(text, BorderLayout.CENTER);
		return card;
	}

	private JPanel buildControls()
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(6, 8, 8, 8));

		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(2, 0, 2, 0);

		style(activeBox);
		activeBox.setToolTipText("Untick to pause without turning the plugin off.");
		p.add(activeBox, c);

		c.gridy++;
		p.add(fieldLabel("Mode"), c);
		c.gridy++;
		fullWidth(modeBox);
		p.add(modeBox, c);

		c.gridy++;
		p.add(fieldLabel("Separation"), c);
		c.gridy++;
		fullWidth(separationBox);
		p.add(separationBox, c);

		c.gridy++;
		p.add(fieldLabel("Max players per tile"), c);
		c.gridy++;
		fullWidth(maxStackSpinner);
		p.add(maxStackSpinner, c);

		c.gridy++;
		style(includeLocalBox);
		includeLocalBox.setToolTipText("Off: you stay put and others step around you. On: you take a spot in the ring too.");
		p.add(includeLocalBox, c);

		c.gridy++;
		style(smoothingBox);
		smoothingBox.setToolTipText("Ease players into place instead of snapping.");
		p.add(smoothingBox, c);

		c.gridy++;
		JPanel offsetRow = new JPanel(new BorderLayout());
		offsetRow.setOpaque(false);
		offsetRow.add(fieldLabel("Test offset"), BorderLayout.WEST);
		testOffsetValue.setFont(FontManager.getRunescapeSmallFont());
		testOffsetValue.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		offsetRow.add(testOffsetValue, BorderLayout.EAST);
		p.add(offsetRow, c);
		c.gridy++;
		testOffsetSlider.setOpaque(false);
		testOffsetSlider.setFocusable(false);
		testOffsetSlider.setToolTipText("Test mode only. 128 units is one tile.");
		fullWidth(testOffsetSlider);
		p.add(testOffsetSlider, c);

		return p;
	}

	private JPanel buildHelp()
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new EmptyBorder(6, 8, 8, 8));
		JLabel text = new JLabel(wrap(
			"1. Make sure the GPU plugin is on.\n"
				+ "2. Set Mode to \"Test: shift my character\". You should be drawn a little to the east.\n"
				+ "3. Set Mode back to \"Spread stacked players\" and stand on someone's tile.\n"
				+ "4. If the status goes red, press Copy report and paste it in a message."));
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		p.add(text, BorderLayout.CENTER);
		return p;
	}

	private void rebuildCheckRows(int count)
	{
		checksPanel.removeAll();
		checkRows.clear();
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.insets = new Insets(1, 0, 1, 0);
		for (int i = 0; i < count; i++)
		{
			JLabel label = new JLabel();
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setIconTextGap(6);
			JLabel value = new JLabel();
			value.setFont(FontManager.getRunescapeSmallFont());
			value.setForeground(Color.WHITE);
			value.setHorizontalAlignment(SwingConstants.RIGHT);

			c.gridx = 0;
			c.weightx = 1;
			c.anchor = GridBagConstraints.WEST;
			c.fill = GridBagConstraints.HORIZONTAL;
			checksPanel.add(label, c);
			c.gridx = 1;
			c.weightx = 0;
			c.anchor = GridBagConstraints.EAST;
			c.fill = GridBagConstraints.NONE;
			checksPanel.add(value, c);
			c.gridy++;
			checkRows.add(new JLabel[]{label, value});
		}
		checksPanel.revalidate();
		checksPanel.repaint();
	}

	private void wireControls()
	{
		activeBox.addActionListener(e -> write(PersonalSpaceConfig.KEY_ACTIVE, activeBox.isSelected()));
		modeBox.addActionListener(e ->
		{
			updateEnabledState();
			Object mode = modeBox.getSelectedItem();
			if (mode != null)
			{
				write(PersonalSpaceConfig.KEY_MODE, mode);
			}
		});
		separationBox.addActionListener(e ->
		{
			Object separation = separationBox.getSelectedItem();
			if (separation != null)
			{
				write(PersonalSpaceConfig.KEY_SEPARATION, separation);
			}
		});
		maxStackSpinner.addChangeListener(e -> write(PersonalSpaceConfig.KEY_MAX_STACK, (Integer) maxStackSpinner.getValue()));
		includeLocalBox.addActionListener(e -> write(PersonalSpaceConfig.KEY_INCLUDE_LOCAL, includeLocalBox.isSelected()));
		smoothingBox.addActionListener(e -> write(PersonalSpaceConfig.KEY_SMOOTHING, smoothingBox.isSelected()));
		testOffsetSlider.addChangeListener(e ->
		{
			testOffsetValue.setText(testOffsetSlider.getValue() + " units");
			if (!testOffsetSlider.getValueIsAdjusting())
			{
				write(PersonalSpaceConfig.KEY_TEST_OFFSET, testOffsetSlider.getValue());
			}
		});
	}

	private <T> void write(String key, T value)
	{
		if (!updatingControls)
		{
			configManager.setConfiguration(PersonalSpaceConfig.GROUP, key, value);
		}
	}

	/** Grey out the settings that don't apply to the selected mode. */
	private void updateEnabledState()
	{
		boolean test = modeBox.getSelectedItem() == PersonalSpaceConfig.Mode.TEST_SHIFT_ME;
		separationBox.setEnabled(!test);
		maxStackSpinner.setEnabled(!test);
		includeLocalBox.setEnabled(!test);
		testOffsetSlider.setEnabled(test);
	}

	private void copyReport()
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(StatusSummary.report(last)), null);
			copyButton.setText("Copied - paste it in a message");
		}
		catch (IllegalStateException e)
		{
			copyButton.setText("Clipboard busy, try again");
		}
		copyReset.setRepeats(false);
		copyReset.restart();
	}

	// ---- small helpers -----------------------------------------------------------------

	private static JLabel sectionLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.BRAND_ORANGE);
		return l;
	}

	private static JLabel fieldLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private static void style(JCheckBox box)
	{
		box.setOpaque(false);
		box.setFocusable(false);
		box.setForeground(Color.WHITE);
	}

	/** Let GridBagLayout stretch the component to the column width instead of its natural width. */
	private static void fullWidth(Component comp)
	{
		comp.setPreferredSize(new Dimension(0, comp.getPreferredSize().height));
		comp.setFocusable(false);
	}

	private static Color colorFor(StatusSummary.Level level)
	{
		switch (level)
		{
			case OK:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case PAUSED:
				return ColorScheme.PROGRESS_INPROGRESS_COLOR;
			case PROBLEM:
				return ColorScheme.PROGRESS_ERROR_COLOR;
			case WAITING:
			default:
				return ColorScheme.MEDIUM_GRAY_COLOR;
		}
	}

	private static int clamp(int v, int min, int max)
	{
		return Math.max(min, Math.min(max, v));
	}

	/** HTML so long text wraps inside the narrow sidebar; newlines become line breaks. */
	private static String wrap(String text)
	{
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
		return "<html><div style='width:" + TEXT_WIDTH_PX + "px'>" + escaped + "</div></html>";
	}

	/** A filled circle, used as a status light. */
	private static final class Dot implements Icon
	{
		private final Color color;
		private final int size;

		Dot(Color color, int size)
		{
			this.color = color;
			this.size = size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(x, y, size, size);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}
	}
}
