package com.nxenon.grpcweb.ui;

import com.nxenon.grpcweb.ui.ExtensionSettings.DetectionMode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/**
 * The suite tab: how the extension decides a message is gRPC-Web, plus the analyzer results.
 *
 * <p>The detection choices are radio buttons because they are genuinely exclusive. The Jython
 * version used five checkboxes that switched each other on and off in an action listener, which
 * could be driven into states that contradicted themselves.
 */
final class SettingsPanel {

    private final JPanel component;

    SettingsPanel(ExtensionSettings settings, AnalyzerPanel analyzerPanel) {
        JPanel settingsColumn = new JPanel();
        settingsColumn.setLayout(new BoxLayout(settingsColumn, BoxLayout.Y_AXIS));
        settingsColumn.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        settingsColumn.add(heading("Body format detection"));
        settingsColumn.add(note(
                "Decides how to read the body of a request or response."));

        JRadioButton automatic = new JRadioButton(
                "Detect from the Content-Type or X-Grpc-Content-Type header");
        JRadioButton forceText = new JRadioButton(
                "Always treat bodies as application/grpc-web-text (base64)");
        JRadioButton forceProto = new JRadioButton(
                "Always treat bodies as application/grpc-web+proto (binary)");

        ButtonGroup detectionGroup = new ButtonGroup();
        detectionGroup.add(automatic);
        detectionGroup.add(forceText);
        detectionGroup.add(forceProto);

        switch (settings.detectionMode()) {
            case AUTOMATIC -> automatic.setSelected(true);
            case FORCE_TEXT -> forceText.setSelected(true);
            case FORCE_PROTO -> forceProto.setSelected(true);
        }
        automatic.addActionListener(
                event -> settings.setDetectionMode(DetectionMode.AUTOMATIC));
        forceText.addActionListener(
                event -> settings.setDetectionMode(DetectionMode.FORCE_TEXT));
        forceProto.addActionListener(
                event -> settings.setDetectionMode(DetectionMode.FORCE_PROTO));

        settingsColumn.add(automatic);
        settingsColumn.add(forceText);
        settingsColumn.add(forceProto);
        settingsColumn.add(Box.createVerticalStrut(14));

        settingsColumn.add(heading("Where the tab appears"));

        JCheckBox showTabAlways = new JCheckBox(
                "Show the tab even when no gRPC-Web content type is detected",
                settings.showTabAlways());
        showTabAlways.setToolTipText(
                "Useful when a target sends a generic Content-Type. Bodies are assumed to be"
                        + " grpc-web-text unless a format is forced above.");
        showTabAlways.addActionListener(
                event -> settings.setShowTabAlways(showTabAlways.isSelected()));
        settingsColumn.add(showTabAlways);

        JCheckBox decodeResponses = new JCheckBox(
                "Decode responses as well as requests", settings.decodeResponses());
        decodeResponses.setToolTipText(
                "Server-streaming responses carry several frames plus a trailer frame.");
        decodeResponses.addActionListener(
                event -> settings.setDecodeResponses(decodeResponses.isSelected()));
        settingsColumn.add(decodeResponses);
        settingsColumn.add(Box.createVerticalStrut(14));

        settingsColumn.add(heading("Analyzing JavaScript"));
        settingsColumn.add(note(
                "Right-click a response in the proxy history or site map and choose"
                        + " \"Analyze gRPC-Web Endpoints\" to pull service routes and message"
                        + " fields out of a gRPC-Web bundle. Results appear below."));

        JPanel settingsWrapper = new JPanel(new BorderLayout());
        settingsWrapper.add(settingsColumn, BorderLayout.NORTH);

        component = new JPanel(new BorderLayout());
        component.add(new JScrollPane(settingsWrapper), BorderLayout.NORTH);
        component.add(analyzerPanel.uiComponent(), BorderLayout.CENTER);
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        return label;
    }

    /**
     * A wrapped block of explanatory text.
     *
     * <p>Uses a read-only text area rather than a {@code JLabel} carrying {@code <html>} markup.
     * Swing's HTML rendering in a label is not guaranteed — it can be switched off, and under Burp
     * the markup was being shown to the user verbatim — so the text is wrapped by the component
     * instead of by a style attribute that may never be interpreted.
     */
    private static JComponent note(String text) {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        // A JTextArea defaults to a monospaced font; match the surrounding labels instead.
        area.setFont(new JLabel().getFont());
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        // BoxLayout would otherwise stretch the area to its maximum height.
        area.setMaximumSize(new Dimension(640, Integer.MAX_VALUE));
        return area;
    }

    Component uiComponent() {
        return component;
    }
}
