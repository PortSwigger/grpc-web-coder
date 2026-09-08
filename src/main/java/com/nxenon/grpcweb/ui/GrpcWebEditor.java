package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.RawEditor;
import com.nxenon.grpcweb.codec.GrpcWebDocument;
import com.nxenon.grpcweb.codec.GrpcWebFormat;
import com.nxenon.grpcweb.protobuf.JsonCodec;
import com.nxenon.grpcweb.protobuf.TypeDefinition;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The decode/edit surface shared by the request and response editor tabs.
 *
 * <p>Two sub-tabs: <em>Payload</em> holds the decoded messages as JSON and is editable, and
 * <em>Type Definition</em> holds the field types. The type definition starts as the decoder's guess
 * and is read-only until the user chooses to edit it; once edited it is applied to every subsequent
 * decode, so a field they retyped from {@code bytes} to {@code string} stays that way.
 */
final class GrpcWebEditor {

    private final MontoyaApi api;
    private final ExtensionSettings settings;
    private final boolean editable;

    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel component;
    private final RawEditor payloadEditor;
    private final RawEditor typeDefinitionEditor;
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton editTypeDefinitionButton = new JButton("Edit type definition");
    private final JButton applyTypeDefinitionButton = new JButton("Apply");
    private final JButton resetTypeDefinitionButton = new JButton("Reset to detected");

    /** Set once the user applies an edited type definition; until then the guess is refreshed. */
    private TypeDefinition userTypeDefinition;

    /**
     * The body currently on display, kept so that applying an edited type definition can redecode
     * immediately rather than making the user reselect the message.
     */
    private ByteArray currentBody;

    /** The format of the message currently displayed, or {@code null} when nothing decoded. */
    private GrpcWebFormat currentFormat;

    /** True when the current body decoded cleanly and may therefore be re-encoded. */
    private boolean decodedSuccessfully;

    GrpcWebEditor(MontoyaApi api, ExtensionSettings settings, boolean editable) {
        this.api = api;
        this.settings = settings;
        this.editable = editable;

        payloadEditor = api.userInterface().createRawEditor(payloadEditorOptions());
        payloadEditor.setEditable(editable);
        tabs.addTab("Payload", payloadEditor.uiComponent());

        // The type definition stays editable even in a read-only view such as proxy history:
        // changing a field's type is how you read a payload correctly, not just how you tamper
        // with one. Creating this editor READ_ONLY would make setEditable(true) a no-op later.
        typeDefinitionEditor = api.userInterface().createRawEditor(EditorOptions.WRAP_LINES);
        typeDefinitionEditor.setEditable(false);
        tabs.addTab("Type Definition", buildTypeDefinitionPanel());

        JPanel root = new JPanel(new BorderLayout());
        root.add(tabs, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        this.component = root;

        editTypeDefinitionButton.addActionListener(event -> enableTypeDefinitionEditing());
        applyTypeDefinitionButton.addActionListener(event -> applyTypeDefinition());
        resetTypeDefinitionButton.addActionListener(event -> resetTypeDefinition());
        applyTypeDefinitionButton.setEnabled(false);
        resetTypeDefinitionButton.setEnabled(false);
    }

    private EditorOptions[] payloadEditorOptions() {
        return editable
                ? new EditorOptions[]{EditorOptions.WRAP_LINES}
                : new EditorOptions[]{EditorOptions.READ_ONLY, EditorOptions.WRAP_LINES};
    }

    private Component buildTypeDefinitionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(typeDefinitionEditor.uiComponent(), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(editTypeDefinitionButton);
        buttons.add(applyTypeDefinitionButton);
        buttons.add(resetTypeDefinitionButton);
        buttons.add(new JLabel(
                "Change a field's \"type\" to reinterpret it. Any \"alt_types\" list shows the"
                        + " other readings of the same bytes."));
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(Box.createHorizontalGlue(), BorderLayout.CENTER);
        return bar;
    }

    Component uiComponent() {
        return component;
    }

    Selection selectedData() {
        Optional<Selection> selection = payloadEditor.selection();
        return selection.orElse(null);
    }

    boolean isModified() {
        return payloadEditor.isModified() || typeDefinitionEditor.isModified();
    }

    /**
     * Whether this message looks like something the tab should offer to decode.
     *
     * <p>Only the headers are examined. Decoding is not attempted here because
     * {@code isEnabledFor} is called for every message the user clicks through, and a tab that
     * appears and disappears depending on whether a body happens to parse is worse than one that is
     * consistently present for a given content type.
     */
    GrpcWebFormat formatFor(HttpMessage message) {
        if (message == null) {
            return null;
        }
        return settings.resolveFormat(
                headerValue(message, "Content-Type"),
                headerValue(message, "X-Grpc-Content-Type"));
    }

    private static String headerValue(HttpMessage message, String name) {
        try {
            return message.headerValue(name);
        } catch (RuntimeException e) {
            // A malformed message can make header parsing throw; absence is the right answer.
            return null;
        }
    }

    /** Renders a message body into the two tabs. */
    void setBody(ByteArray body, GrpcWebFormat format) {
        currentFormat = format;
        currentBody = body;
        decodedSuccessfully = false;

        if (body == null || format == null) {
            payloadEditor.setContents(ByteArray.byteArray(new byte[0]));
            typeDefinitionEditor.setContents(ByteArray.byteArray(new byte[0]));
            setStatus("No gRPC-Web body to decode.");
            return;
        }

        if (body.length() == 0) {
            payloadEditor.setContents(ByteArray.byteArray(new byte[0]));
            typeDefinitionEditor.setContents(ByteArray.byteArray(new byte[0]));
            setStatus("Body is empty.");
            return;
        }

        try {
            GrpcWebDocument document = userTypeDefinition == null
                    ? GrpcWebDocument.decode(body.getBytes(), format)
                    : GrpcWebDocument.decode(body.getBytes(), format, userTypeDefinition);

            payloadEditor.setContents(text(document.toPayloadJson()));
            if (userTypeDefinition == null) {
                typeDefinitionEditor.setContents(text(document.toTypeDefinitionJson()));
            }
            decodedSuccessfully = true;
            setStatus(String.format(
                    "Decoded %d frame%s as %s%s.",
                    document.frameCount(),
                    document.frameCount() == 1 ? "" : "s",
                    format.canonicalContentType(),
                    userTypeDefinition == null ? "" : " using your type definition"));
        } catch (Exception e) {
            // Any failure must land in the tab as a message. Bodies come from the target and are
            // routinely malformed, truncated or simply not gRPC-Web at all.
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            payloadEditor.setContents(text(
                    "Could not decode this body as " + format.canonicalContentType() + ".\n\n"
                            + reason
                            + "\n\nThe original message is forwarded unchanged."));
            if (userTypeDefinition == null) {
                typeDefinitionEditor.setContents(text("{}"));
            }
            setStatus("Not decoded: " + reason);
        }
    }

    /**
     * Re-encodes the edited tabs into a body.
     *
     * @return the new body, or {@code null} if nothing should change — either the body never
     *         decoded, or re-encoding failed and the original must be preserved
     */
    ByteArray encodedBody() {
        if (!decodedSuccessfully || currentFormat == null || !editable) {
            return null;
        }
        try {
            String payloadJson = readEditor(payloadEditor);
            String typeDefinitionJson = readEditor(typeDefinitionEditor);
            byte[] encoded =
                    GrpcWebDocument.encode(payloadJson, typeDefinitionJson, currentFormat);
            setStatus("Encoded " + encoded.length + " byte body.");
            return ByteArray.byteArray(encoded);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            setStatus("Not encoded, message left unchanged: " + reason);
            api.logging().logToError("gRPC-Web Coder could not encode the edited payload: " + reason);
            return null;
        }
    }

    private void enableTypeDefinitionEditing() {
        typeDefinitionEditor.setEditable(true);
        applyTypeDefinitionButton.setEnabled(true);
        resetTypeDefinitionButton.setEnabled(true);
        setStatus("Type definition is editable. Change a field's \"type\", then click Apply.");
    }

    /** Adopts the edited type definition and redecodes the body with it straight away. */
    private void applyTypeDefinition() {
        String json = readEditor(typeDefinitionEditor);
        if (json.isBlank()) {
            setStatus("Type definition is empty; nothing to apply.");
            return;
        }
        try {
            userTypeDefinition = JsonCodec.jsonToTypeDefinition(json);
        } catch (Exception e) {
            setStatus("Type definition not applied: " + e.getMessage());
            return;
        }
        // Redecode from the body that is on display, so the Payload tab updates immediately.
        setBody(currentBody, currentFormat);
    }

    private void resetTypeDefinition() {
        userTypeDefinition = null;
        typeDefinitionEditor.setEditable(false);
        applyTypeDefinitionButton.setEnabled(false);
        resetTypeDefinitionButton.setEnabled(false);
        setBody(currentBody, currentFormat);
        setStatus("Type definition reset to the detected one.");
    }

    /**
     * Adopts the type definition currently in the editor if the user changed it without clicking
     * Apply, so their edit is not silently lost when the message is forwarded.
     */
    void adoptEditedTypeDefinition() {
        if (!typeDefinitionEditor.isModified()) {
            return;
        }
        String json = readEditor(typeDefinitionEditor);
        if (json.isBlank()) {
            return;
        }
        try {
            userTypeDefinition = JsonCodec.jsonToTypeDefinition(json);
        } catch (Exception e) {
            setStatus("Type definition not applied: " + e.getMessage());
        }
    }

    private static String readEditor(RawEditor editor) {
        ByteArray contents = editor.getContents();
        return contents == null ? "" : new String(contents.getBytes(), StandardCharsets.UTF_8);
    }

    private static ByteArray text(String value) {
        return ByteArray.byteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }
}
