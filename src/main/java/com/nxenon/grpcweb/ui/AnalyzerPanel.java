package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import com.nxenon.grpcweb.analyze.GrpcEndpoint;
import com.nxenon.grpcweb.analyze.GrpcMessageField;
import com.nxenon.grpcweb.analyze.JsAnalyzer;
import com.nxenon.grpcweb.export.AnalysisExporter;
import com.nxenon.grpcweb.export.AnalysisResults;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;

/**
 * Shows what "Analyze gRPC-Web Endpoints" found, and exports it.
 *
 * <p>The Jython version printed findings to Burp's extension output as ASCII-art tables, so results
 * from several files ran together and nothing could be sorted, copied or saved. Here findings
 * accumulate into two sortable tables and can be written out as JSON, CSV, a reconstructed
 * {@code .proto}, or type definitions ready to paste into the editor's Type Definition tab.
 *
 * <p>All mutation happens on the Swing event dispatch thread: analysis runs on a background thread
 * and hands its results here, so every entry point re-dispatches rather than assuming a caller.
 */
final class AnalyzerPanel {

    private final AnalysisResults results = new AnalysisResults();
    private final ResultsExportAction exportAction;

    private final EndpointTableModel endpointModel = new EndpointTableModel();
    private final FieldTableModel fieldModel = new FieldTableModel();
    private final JComboBox<AnalysisExporter.Format> formatChooser =
            new JComboBox<>(AnalysisExporter.Format.values());
    private final JButton exportButton = new JButton("Export to file...");
    private final JButton copyButton = new JButton("Copy to clipboard");
    private final JButton clearButton = new JButton("Clear results");
    private final JLabel summaryLabel = new JLabel("No JavaScript analyzed yet.");
    private final JPanel component;

    AnalyzerPanel(MontoyaApi api) {
        this.exportAction = new ResultsExportAction(api);

        JTable endpointTable = new JTable(endpointModel);
        endpointTable.setRowSorter(new TableRowSorter<>(endpointModel));
        endpointTable.setAutoCreateRowSorter(false);
        endpointTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        JTable fieldTable = new JTable(fieldModel);
        fieldTable.setRowSorter(new TableRowSorter<>(fieldModel));
        fieldTable.setAutoCreateRowSorter(false);

        JPanel endpointPanel = new JPanel(new BorderLayout());
        endpointPanel.setBorder(BorderFactory.createTitledBorder("Endpoints"));
        endpointPanel.add(new JScrollPane(endpointTable), BorderLayout.CENTER);

        JPanel fieldPanel = new JPanel(new BorderLayout());
        fieldPanel.setBorder(BorderFactory.createTitledBorder("Message fields"));
        fieldPanel.add(new JScrollPane(fieldTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, endpointPanel, fieldPanel);
        split.setResizeWeight(0.4);

        component = new JPanel(new BorderLayout());
        component.add(buildToolbar(), BorderLayout.NORTH);
        component.add(split, BorderLayout.CENTER);
    }

    private Component buildToolbar() {
        formatChooser.setSelectedItem(AnalysisExporter.Format.JSON);
        formatChooser.addActionListener(event -> updateFormatTooltip());
        updateFormatTooltip();

        exportAction(exportButton, false);
        exportAction(copyButton, true);
        clearButton.addActionListener(event -> clear());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(new JLabel("Export as:"));
        toolbar.add(formatChooser);
        toolbar.add(exportButton);
        toolbar.add(copyButton);
        toolbar.add(clearButton);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(toolbar, BorderLayout.NORTH);
        JPanel summaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        summaryRow.add(summaryLabel);
        wrapper.add(summaryRow, BorderLayout.SOUTH);
        return wrapper;
    }

    private void exportAction(JButton button, boolean toClipboard) {
        button.addActionListener(event -> {
            AnalysisExporter.Format format = selectedFormat();
            if (toClipboard) {
                exportAction.copyToClipboard(results, format, this::setSummary);
            } else {
                exportAction.saveToFile(results, format, this::setSummary);
            }
        });
    }

    private AnalysisExporter.Format selectedFormat() {
        AnalysisExporter.Format format = formatChooser.getItemAt(formatChooser.getSelectedIndex());
        return format == null ? AnalysisExporter.Format.JSON : format;
    }

    private void updateFormatTooltip() {
        formatChooser.setToolTipText(selectedFormat().description());
    }

    Component uiComponent() {
        return component;
    }

    /**
     * Adds one script's findings to the tables.
     *
     * @param source a label for where the results came from, shown in the table
     */
    void addResult(String source, JsAnalyzer.Result result) {
        SwingUtilities.invokeLater(() -> {
            int added = results.add(source, result);
            endpointModel.refresh();
            fieldModel.refresh();
            if (added == 0) {
                setSummary("Already had every finding from " + source + ".");
                return;
            }
            setSummary(String.format(
                    "%d endpoint(s) and %d field(s) across %d message(s) so far.",
                    results.endpointCount(),
                    results.fieldCount(),
                    results.messageNames().size()));
        });
    }

    /** Reports a script that yielded nothing, so a run never looks like it silently failed. */
    void addEmptyResult(String source) {
        SwingUtilities.invokeLater(() -> setSummary(
                "No gRPC-Web definitions found in " + source + "."));
    }

    private void clear() {
        SwingUtilities.invokeLater(() -> {
            results.clear();
            endpointModel.refresh();
            fieldModel.refresh();
            setSummary("No JavaScript analyzed yet.");
        });
    }

    private void setSummary(String message) {
        summaryLabel.setText(message);
    }

    /** Stops the export worker thread. Called from the extension's unloading handler. */
    void shutdown() {
        exportAction.shutdown();
    }

    /** Endpoint rows, read straight from the results model. */
    private final class EndpointTableModel extends AbstractTableModel {

        private static final String[] COLUMNS =
                {"Source", "Path", "Service", "Method", "Call style", "Request type",
                        "Response type"};

        void refresh() {
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return results.endpoints().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            AnalysisResults.EndpointEntry entry = results.endpoints().get(row);
            GrpcEndpoint endpoint = entry.endpoint();
            return switch (column) {
                case 0 -> entry.source();
                case 1 -> endpoint.path();
                case 2 -> endpoint.service();
                case 3 -> endpoint.method();
                case 4 -> endpoint.callStyle().label();
                case 5 -> endpoint.requestType();
                case 6 -> endpoint.responseType();
                default -> "";
            };
        }
    }

    /** Message field rows, read straight from the results model. */
    private final class FieldTableModel extends AbstractTableModel {

        private static final String[] COLUMNS =
                {"Source", "Message", "Field", "Number", "Type", "jspb accessor"};

        void refresh() {
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return results.fields().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            // Field numbers must sort numerically, not as text.
            return column == 3 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            AnalysisResults.FieldEntry entry = results.fields().get(row);
            GrpcMessageField field = entry.field();
            return switch (column) {
                case 0 -> entry.source();
                case 1 -> field.messageName();
                case 2 -> field.fieldName();
                case 3 -> field.fieldNumber();
                case 4 -> field.inferredType();
                case 5 -> field.jspbSetter();
                default -> "";
            };
        }
    }
}
