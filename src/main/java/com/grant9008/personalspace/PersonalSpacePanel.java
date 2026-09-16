package com.grant9008.personalspace;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The Personal Space sidebar.
 *
 * <p>Top: an on/off switch and a one-line status. Middle: the everyday settings as tap-to-pick
 * buttons. Bottom: a folded-away Troubleshooting section with test mode, live checks and a
 * copyable report.
 *
 * <p>Swing thread only. The plugin pushes a fresh {@link Snapshot} a couple of times a second via
 * {@link #update}, and calls {@link #refreshControls} whenever the config changes (including from
 * RuneLite's own settings screen). Controls write straight to the config, so both stay in sync.
 */
final class PersonalSpacePanel extends PluginPanel
{
	private static final int TEXT_WIDTH_PX = 150;
	private static final Color SELECTED_TEXT = new Color(30, 30, 30);

	private final ConfigManager configManager;
	private final PersonalSpaceConfig config;

	private final ToggleSwitch activeSwitch = new ToggleSwitch();
	private final JLabel statusDot = new JLabel();
	private final JLabel statusTitle = new JLabel();
	private final JLabel statusDetail = new JLabel();

	private final PillGroup<Integer> perTilePills = new PillGroup<>(
		new Integer[]{2, 3, 4, 5}, new String[]{"2", "3", "4", "5"});
	private final PillGroup<PersonalSpaceConfig.Separation> spacingPills = new PillGroup<>(
		PersonalSpaceConfig.Separation.values(), labels(PersonalSpaceConfig.Separation.values()));
	private final PillGroup<PersonalSpaceConfig.Arrangement> arrangementPills = new PillGroup<>(
		PersonalSpaceConfig.Arrangement.values(), new String[]{"Auto", "Circle", "Side by side"});
	private final ToggleSwitch includeMeSwitch = new ToggleSwitch();
	private final ToggleSwitch smoothSwitch = new ToggleSwitch();

	private final JPanel troubleshootingBody = new JPanel(new GridBagLayout());
	private final JLabel troubleshootingHeader = new JLabel("Troubleshooting");
	private final ToggleSwitch testModeSwitch = new ToggleSwitch();
	private final JSlider testOffsetSlider = new JSlider(PersonalSpaceConfig.MIN_TEST_OFFSET, PersonalSpaceConfig.MAX_TEST_OFFSET, 32);
	private final JLabel testOffsetValue = new JLabel();
	private final JPanel checksPanel = new JPanel(new GridBagLayout());
	private final List<JLabel[]> checkRows = new ArrayList<>();
	private final JButton copyButton = new JButton("Copy report");
	private final Timer copyReset = new Timer(2500, e -> copyButton.setText("Copy report"));

	private Snapshot last = new Snapshot();

	PersonalSpacePanel(ConfigManager configManager, PersonalSpaceConfig config)
	{
		this.configManager = configManager;
		this.config = config;

		setLayout(new GridBagLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		GridBagConstraints c = column();
		add(buildHeader(), c);

		c.gridy++;
		add(buildStatus(), c);

		c.gridy++;
		c.insets = new Insets(4, 0, 4, 0);
		add(buildSettings(), c);

		c.gridy++;
		c.insets = new Insets(10, 0, 0, 0);
		add(buildTroubleshooting(), c);

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
		statusDot.setIcon(new Dot(colorFor(h.level), 10));
		statusTitle.setText(wrap(h.title));
		statusDetail.setText(wrap(h.detail));

		if (!troubleshootingBody.isVisible())
		{
			return;
		}
		List<StatusSummary.Check> checks = StatusSummary.checks(s);
		if (checks.size() != checkRows.size())
		{
			rebuildCheckRows(checks.size());
		}
		for (int i = 0; i < checks.size(); i++)
		{
			StatusSummary.Check check = checks.get(i);
			JLabel[] row = checkRows.get(i);
			row[0].setIcon(new Dot(colorFor(check.level), 7));
			row[0].setText(check.label);
			row[1].setText(check.value);
		}
	}

	/** Swing thread. Set every control from the current config. Controls only write on a user click. */
	void refreshControls()
	{
		activeSwitch.setOn(config.active());
		perTilePills.select(clamp(config.maxStack(), PersonalSpaceConfig.MIN_STACK, PersonalSpaceConfig.MAX_STACK));
		spacingPills.select(config.separation());
		arrangementPills.select(config.arrangement());
		includeMeSwitch.setOn(config.includeLocalPlayer());
		smoothSwitch.setOn(config.smoothing());
		testModeSwitch.setOn(config.mode() == PersonalSpaceConfig.Mode.TEST_SHIFT_ME);
		int offset = clamp(config.testOffset(), PersonalSpaceConfig.MIN_TEST_OFFSET, PersonalSpaceConfig.MAX_TEST_OFFSET);
		if (!testOffsetSlider.getValueIsAdjusting() && testOffsetSlider.getValue() != offset)
		{
			testOffsetSlider.setValue(offset);
		}
		testOffsetValue.setText(testOffsetSlider.getValue() + " units");
		setEverydayEnabled(config.active());
	}

	// ---- building ----------------------------------------------------------------------

	private JPanel buildHeader()
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(0, 0, 8, 0));
		JLabel title = new JLabel("Personal Space");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		p.add(title, BorderLayout.WEST);
		activeSwitch.setToolTipText("Turn spreading out crowds on or off");
		p.add(activeSwitch, BorderLayout.EAST);
		return p;
	}

	private JPanel buildStatus()
	{
		JPanel card = new JPanel(new BorderLayout(8, 0));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 8, 8, 8));

		statusDot.setVerticalAlignment(SwingConstants.TOP);
		statusDot.setBorder(new EmptyBorder(3, 0, 0, 0));
		card.add(statusDot, BorderLayout.WEST);

		JPanel text = new JPanel(new BorderLayout(0, 3));
		text.setOpaque(false);
		statusTitle.setFont(FontManager.getRunescapeBoldFont());
		statusTitle.setForeground(Color.WHITE);
		statusDetail.setFont(FontManager.getRunescapeSmallFont());
		statusDetail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.add(statusTitle, BorderLayout.NORTH);
		text.add(statusDetail, BorderLayout.CENTER);
		card.add(text, BorderLayout.CENTER);
		return card;
	}

	private JPanel buildSettings()
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		GridBagConstraints c = column();

		c.insets = new Insets(8, 0, 4, 0);
		p.add(sectionLabel("Players per tile"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		perTilePills.setToolTipText("How many players on one tile get their own spot");
		p.add(perTilePills, c);

		c.gridy++;
		c.insets = new Insets(10, 0, 4, 0);
		p.add(sectionLabel("Spacing"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		spacingPills.setToolTipText("How far apart players are drawn. Wide still stays inside the tile.");
		p.add(spacingPills, c);

		c.gridy++;
		c.insets = new Insets(10, 0, 4, 0);
		p.add(sectionLabel("Arrangement"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		arrangementPills.setToolTipText("Auto: players facing the same way (anvil, bank booth, range, fire) stand side by side; everyone else forms a circle.");
		p.add(arrangementPills, c);

		c.gridy++;
		c.insets = new Insets(12, 0, 0, 0);
		p.add(switchRow("Move my character too", includeMeSwitch,
			"Off: you stay where you are and others step around you."), c);
		c.gridy++;
		c.insets = new Insets(6, 0, 0, 0);
		p.add(switchRow("Smooth movement", smoothSwitch,
			"Players glide into place instead of jumping."), c);
		return p;
	}

	private JPanel buildTroubleshooting()
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		GridBagConstraints c = column();

		troubleshootingHeader.setFont(FontManager.getRunescapeSmallFont());
		troubleshootingHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		troubleshootingHeader.setIcon(new Arrow(false));
		troubleshootingHeader.setIconTextGap(6);
		troubleshootingHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		troubleshootingHeader.setBorder(new EmptyBorder(4, 0, 4, 0));
		troubleshootingHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				setTroubleshootingOpen(!troubleshootingBody.isVisible());
			}
		});
		p.add(troubleshootingHeader, c);

		troubleshootingBody.setOpaque(false);
		GridBagConstraints b = column();
		b.insets = new Insets(4, 0, 0, 0);
		troubleshootingBody.add(switchRow("Test: shift only me", testModeSwitch,
			"Ignores everyone else and draws your own character a little to the east, to check the effect works."), b);

		b.gridy++;
		JPanel offsetRow = new JPanel(new BorderLayout());
		offsetRow.setOpaque(false);
		JLabel offsetLabel = new JLabel("Test distance");
		offsetLabel.setFont(FontManager.getRunescapeSmallFont());
		offsetLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		offsetRow.add(offsetLabel, BorderLayout.WEST);
		testOffsetValue.setFont(FontManager.getRunescapeSmallFont());
		testOffsetValue.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		offsetRow.add(testOffsetValue, BorderLayout.EAST);
		troubleshootingBody.add(offsetRow, b);

		b.gridy++;
		b.insets = new Insets(0, 0, 0, 0);
		testOffsetSlider.setOpaque(false);
		testOffsetSlider.setFocusable(false);
		testOffsetSlider.setToolTipText("How far test mode shifts your character. 128 units is one tile.");
		testOffsetSlider.setPreferredSize(new Dimension(0, testOffsetSlider.getPreferredSize().height));
		troubleshootingBody.add(testOffsetSlider, b);

		b.gridy++;
		b.insets = new Insets(8, 0, 0, 0);
		checksPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		checksPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
		troubleshootingBody.add(checksPanel, b);

		b.gridy++;
		copyButton.setFocusable(false);
		copyButton.setToolTipText("Copies everything above as text, to paste into a bug report.");
		copyButton.addActionListener(e -> copyReport());
		troubleshootingBody.add(copyButton, b);

		c.gridy++;
		p.add(troubleshootingBody, c);
		troubleshootingBody.setVisible(false);
		return p;
	}

	/** Open or fold the Troubleshooting section. Package-private for the screenshot harness. */
	void setTroubleshootingOpen(boolean open)
	{
		troubleshootingBody.setVisible(open);
		troubleshootingHeader.setIcon(new Arrow(open));
		if (open)
		{
			update(last);
		}
		revalidate();
		repaint();
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
		activeSwitch.onToggle(on ->
		{
			write(PersonalSpaceConfig.KEY_ACTIVE, on);
			setEverydayEnabled(on);
		});
		perTilePills.onSelect(n -> write(PersonalSpaceConfig.KEY_MAX_STACK, n));
		spacingPills.onSelect(v -> write(PersonalSpaceConfig.KEY_SEPARATION, v));
		arrangementPills.onSelect(v -> write(PersonalSpaceConfig.KEY_ARRANGEMENT, v));
		includeMeSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_INCLUDE_LOCAL, on));
		smoothSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_SMOOTHING, on));
		testModeSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_MODE,
			on ? PersonalSpaceConfig.Mode.TEST_SHIFT_ME : PersonalSpaceConfig.Mode.SPREAD));
		testOffsetSlider.addChangeListener(e ->
		{
			testOffsetValue.setText(testOffsetSlider.getValue() + " units");
			if (!testOffsetSlider.getValueIsAdjusting() && testOffsetSlider.getValue() != config.testOffset())
			{
				write(PersonalSpaceConfig.KEY_TEST_OFFSET, testOffsetSlider.getValue());
			}
		});
	}

	private <T> void write(String key, T value)
	{
		if (configManager != null)
		{
			configManager.setConfiguration(PersonalSpaceConfig.GROUP, key, value);
		}
	}

	/** Dim the everyday settings while the whole effect is switched off. */
	private void setEverydayEnabled(boolean enabled)
	{
		perTilePills.setEnabled(enabled);
		spacingPills.setEnabled(enabled);
		arrangementPills.setEnabled(enabled);
		includeMeSwitch.setEnabled(enabled);
		smoothSwitch.setEnabled(enabled);
	}

	private void copyReport()
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(StatusSummary.report(last)), null);
			copyButton.setText("Copied!");
		}
		catch (IllegalStateException e)
		{
			copyButton.setText("Clipboard busy, try again");
		}
		copyReset.setRepeats(false);
		copyReset.restart();
	}

	// ---- small helpers -----------------------------------------------------------------

	private static GridBagConstraints column()
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTH;
		return c;
	}

	private static JLabel sectionLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private static JPanel switchRow(String text, ToggleSwitch toggle, String tooltip)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeFont());
		label.setForeground(Color.WHITE);
		label.setToolTipText(tooltip);
		toggle.setToolTipText(tooltip);
		row.add(label, BorderLayout.WEST);
		row.add(toggle, BorderLayout.EAST);
		// Clicking the words flips the switch too.
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle.click();
			}
		});
		return row;
	}

	private static String[] labels(Object[] values)
	{
		String[] out = new String[values.length];
		for (int i = 0; i < values.length; i++)
		{
			out[i] = values[i].toString();
		}
		return out;
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

	/** HTML so long text wraps inside the narrow sidebar. */
	private static String wrap(String text)
	{
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><div style='width:" + TEXT_WIDTH_PX + "px'>" + escaped + "</div></html>";
	}

	// ---- controls ----------------------------------------------------------------------

	/** An on/off switch drawn in RuneLite's colours. Fires only on a user click. */
	private static final class ToggleSwitch extends JComponent
	{
		private static final int W = 32;
		private static final int H = 18;
		private boolean on;
		private Consumer<Boolean> listener = v ->
		{
		};

		ToggleSwitch()
		{
			setPreferredSize(new Dimension(W, H));
			setMinimumSize(new Dimension(W, H));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					click();
				}
			});
		}

		void onToggle(Consumer<Boolean> listener)
		{
			this.listener = listener;
		}

		void setOn(boolean on)
		{
			if (this.on != on)
			{
				this.on = on;
				repaint();
			}
		}

		void click()
		{
			if (!isEnabled())
			{
				return;
			}
			on = !on;
			repaint();
			listener.accept(on);
		}

		@Override
		public void setEnabled(boolean enabled)
		{
			super.setEnabled(enabled);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int y = (getHeight() - H) / 2;
			int x = getWidth() - W;
			Color track = on ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR;
			if (!isEnabled())
			{
				track = track.darker().darker();
			}
			g2.setColor(track);
			g2.fillRoundRect(x, y, W, H, H, H);
			int knob = H - 4;
			int knobX = on ? x + W - knob - 2 : x + 2;
			g2.setColor(isEnabled() ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
			g2.fillOval(knobX, y + 2, knob, knob);
			g2.dispose();
		}
	}

	/** A row of buttons where exactly one is picked. Fires only on a user click. */
	private static final class PillGroup<T> extends JPanel
	{
		private final T[] values;
		private final JLabel[] pills;
		private int selected = -1;
		private int hovered = -1;
		private Consumer<T> listener = v ->
		{
		};

		PillGroup(T[] values, String[] labels)
		{
			super(new GridLayout(1, values.length, 4, 0));
			setOpaque(false);
			this.values = values;
			this.pills = new JLabel[values.length];
			for (int i = 0; i < values.length; i++)
			{
				final int index = i;
				JLabel pill = new JLabel(labels[i], SwingConstants.CENTER);
				pill.setOpaque(true);
				pill.setFont(FontManager.getRunescapeSmallFont());
				pill.setBorder(new EmptyBorder(6, 2, 6, 2));
				pill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				pill.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseClicked(MouseEvent e)
					{
						if (!PillGroup.this.isEnabled() || index == selected)
						{
							return;
						}
						select(PillGroup.this.values[index]);
						listener.accept(PillGroup.this.values[index]);
					}

					@Override
					public void mouseEntered(MouseEvent e)
					{
						hovered = index;
						restyle();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						hovered = -1;
						restyle();
					}
				});
				pills[i] = pill;
				add(pill);
			}
			restyle();
		}

		void onSelect(Consumer<T> listener)
		{
			this.listener = listener;
		}

		void select(T value)
		{
			for (int i = 0; i < values.length; i++)
			{
				if (values[i].equals(value))
				{
					selected = i;
				}
			}
			restyle();
		}

		@Override
		public void setToolTipText(String text)
		{
			super.setToolTipText(text);
			for (JLabel pill : pills)
			{
				pill.setToolTipText(text);
			}
		}

		@Override
		public void setEnabled(boolean enabled)
		{
			super.setEnabled(enabled);
			restyle();
		}

		private void restyle()
		{
			for (int i = 0; i < pills.length; i++)
			{
				Color bg;
				Color fg;
				if (i == selected)
				{
					bg = ColorScheme.BRAND_ORANGE;
					fg = SELECTED_TEXT;
				}
				else if (i == hovered && isEnabled())
				{
					bg = ColorScheme.MEDIUM_GRAY_COLOR;
					fg = Color.WHITE;
				}
				else
				{
					bg = ColorScheme.DARKER_GRAY_COLOR;
					fg = ColorScheme.LIGHT_GRAY_COLOR;
				}
				if (!isEnabled())
				{
					bg = bg.darker();
					fg = fg.darker();
				}
				pills[i].setBackground(bg);
				pills[i].setForeground(fg);
			}
		}
	}

	/** A small filled circle, used as a status light. */
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

	/** A small chevron: pointing right when folded, down when open. */
	private static final class Arrow implements Icon
	{
		private final boolean open;

		Arrow(boolean open)
		{
			this.open = open;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			if (open)
			{
				g2.drawPolyline(new int[]{x + 1, x + 4, x + 7}, new int[]{y + 3, y + 6, y + 3}, 3);
			}
			else
			{
				g2.drawPolyline(new int[]{x + 3, x + 6, x + 3}, new int[]{y + 1, y + 4, y + 7}, 3);
			}
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 8;
		}

		@Override
		public int getIconHeight()
		{
			return 8;
		}
	}
}
