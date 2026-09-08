package com.nxenon.grpcweb.ui;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds "Analyze gRPC-Web Endpoints" to the right-click menu.
 *
 * <p>The action is offered for any selection with a response, and for the message currently open in
 * an editor, so it works from the proxy history, the site map and Repeater alike.
 */
final class GrpcWebContextMenu implements ContextMenuItemsProvider {

    private final JsAnalysisRunner runner;

    GrpcWebContextMenu(JsAnalysisRunner runner) {
        this.runner = runner;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<HttpRequestResponse> selected = selectedMessages(event);
        if (selected.isEmpty()) {
            return List.of();
        }

        JMenuItem item = new JMenuItem(selected.size() == 1
                ? "Analyze gRPC-Web Endpoints"
                : "Analyze gRPC-Web Endpoints (" + selected.size() + " responses)");
        // The listener runs on the event dispatch thread, so it only queues the work.
        item.addActionListener(actionEvent -> runner.analyze(selected));
        return List.of(item);
    }

    private static List<HttpRequestResponse> selectedMessages(ContextMenuEvent event) {
        List<HttpRequestResponse> messages = new ArrayList<>(event.selectedRequestResponses());
        if (messages.isEmpty()) {
            event.messageEditorRequestResponse()
                    .map(editor -> editor.requestResponse())
                    .ifPresent(messages::add);
        }
        messages.removeIf(message -> message == null || !message.hasResponse());
        return messages;
    }
}
