/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.lsp.client.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.netbeans.api.editor.document.LineDocument;
import org.netbeans.api.editor.document.LineDocumentUtils;
import org.netbeans.api.editor.mimelookup.MimeLookup;
import org.netbeans.api.lsp.Completion;
import org.netbeans.api.lsp.Completion.Context;
import org.netbeans.api.lsp.Completion.TriggerKind;
import org.netbeans.modules.lsp.client.Utils;
import org.netbeans.spi.lsp.ErrorProvider;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.util.RequestProcessor;
import org.openide.util.RequestProcessor.Task;

public class BridgingLanguageServer implements LanguageServer, LanguageClientAware {

    private static final int DIAGNOSTIC_DELAY = 500;
    private static final RequestProcessor WORKER = new RequestProcessor(BridgingLanguageServer.class.getName() + "-worker", 1, false, false);
    private static final RequestProcessor BACKGROUND = new RequestProcessor(BridgingLanguageServer.class.getName() + "-background", 1, false, false);

    private final Map<FileObject, Task> runDiagnostics = new WeakHashMap<>();
    private LanguageClient client;

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities serverCaps = new ServerCapabilities();
        serverCaps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        ServerInfo serverInfo = new ServerInfo();
        InitializeResult initResult = new InitializeResult(serverCaps, serverInfo);
        CompletableFuture<InitializeResult> result = new CompletableFuture<>();

        result.complete(initResult);

        return result;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void exit() {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    private void reRunDiagnostics(FileObject file) {
        runDiagnostics.computeIfAbsent(file, x -> BACKGROUND.create(() -> {
            try {
                if (client == null) {
                    return ;
                }

                EditorCookie ec = file.getLookup().lookup(EditorCookie.class);
                Document doc = ec.openDocument();
                List<Diagnostic> diagnostics = new ArrayList<>();

                for (ErrorProvider.Kind kind : ErrorProvider.Kind.values()) {
                    ErrorProvider.Context ctx = new ErrorProvider.Context(file, kind);

                    for (ErrorProvider provider : MimeLookup.getLookup(file.getMIMEType()).lookupAll(ErrorProvider.class)) {
                        for (var diag : provider.computeErrors(ctx)) {
                            if (diag.getSeverity() == null) {
                                System.err.println("!!!!");
                            }
                            DiagnosticSeverity severity = diag.getSeverity() != null ? DiagnosticSeverity.valueOf(diag.getSeverity().name()) : null;
                            diagnostics.add(new Diagnostic(new Range(Utils.createPosition(doc, diag.getStartPosition().getOffset()), Utils.createPosition(doc, diag.getEndPosition().getOffset())), diag.getDescription(), severity,/*XXX*/ null, diag.getCode()));
                        }
                    }
                }
                client.publishDiagnostics(new PublishDiagnosticsParams(Utils.toURI(file), diagnostics));
            } catch (Exception ex) {
                //TODO:
                throw new IllegalStateException(ex);
            }
        })).schedule(DIAGNOSTIC_DELAY);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                FileObject file = Utils.fromURI(params.getTextDocument().getUri());
                reRunDiagnostics(file);
            }
            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                FileObject file = Utils.fromURI(params.getTextDocument().getUri());
                reRunDiagnostics(file);
            }
            @Override
            public void didClose(DidCloseTextDocumentParams params) {
            }
            @Override
            public void didSave(DidSaveTextDocumentParams params) {
            }
            @Override
            public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
                CompletableFuture<Either<List<CompletionItem>, CompletionList>> result = new CompletableFuture<>();
                try {
                    FileObject file = Utils.fromURI(position.getTextDocument().getUri());
                    EditorCookie ec = file.getLookup().lookup(EditorCookie.class);
                    Document doc = ec.openDocument();
                    List<CompletionItem> items = new ArrayList<>();
                    TriggerKind triggerKind = TriggerKind.Invoked; //XXX??
                    Character triggerCharacter = null;
                    if (position.getContext() != null) {
                        triggerKind = TriggerKind.valueOf(position.getContext().getTriggerKind().name());
                        triggerCharacter = position.getContext().getTriggerKind() == CompletionTriggerKind.TriggerCharacter ? position.getContext().getTriggerCharacter().charAt(0) : null;
                    }
                    boolean complete = Completion.collect(doc, Utils.getOffset(doc, position.getPosition()), new Context(triggerKind, triggerCharacter), completion -> {
                        CompletionItem item = convertCompletionItem(doc, completion);
                        items.add(item);
                    });
                    CompletionList resultValue = new CompletionList(!complete, items);
                    result.complete(Either.forRight(resultValue));
                } catch (IOException ex) {
                    result.completeExceptionally(ex);
                }
                return result;
            }

            @Override
            public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
                CompletableFuture<CompletionItem> result = new CompletableFuture<>();
                CompletionResolutionData completionData = (CompletionResolutionData) unresolved.getData();
                if (completionData != null) {
                    Completion completion = completionData.completion();
                    Document doc = completionData.doc();
                    WORKER.post(() -> {
                        if (completion.getDetail() != null) {
                            try {
                                String detail = completion.getDetail().get();
                                if (detail != null) {
                                    unresolved.setDetail(detail);
                                }
                            } catch (Exception ex) {
                            }
                        }
                        if (completion.getAdditionalTextEdits() != null) {
                            try {
                                List<org.netbeans.api.lsp.TextEdit> additionalTextEdits = completion.getAdditionalTextEdits().get();
                                if (additionalTextEdits != null && !additionalTextEdits.isEmpty()) {
                                    unresolved.setAdditionalTextEdits(additionalTextEdits.stream().map(ed -> {
                                        return new TextEdit(new Range(createPosition(doc, ed.getStartOffset()), createPosition(doc, ed.getEndOffset())), ed.getNewText());
                                    }).collect(Collectors.toList()));
                                }
                            } catch (Exception ex) {
                            }
                        }
                        if (completion.getDocumentation() != null) {
                            try {
                                String documentation = completion.getDocumentation().getNow(null);
                                if (documentation != null) {
                                    MarkupContent markup = new MarkupContent();
                                    markup.setKind("html");
                                    markup.setValue(documentation);
                                    unresolved.setDocumentation(markup);
                                }
                            } catch (Exception ex) {
                            }
                        }
                        result.complete(unresolved);
                    });
                } else {
                    result.complete(unresolved);
                }
                return result;
            }
        };
    }

    private CompletionItem convertCompletionItem(Document doc, Completion completion) {
        CompletionItem item = new CompletionItem(completion.getLabel());
        if (completion.getLabelDetail() != null || completion.getLabelDescription() != null) {
            CompletionItemLabelDetails labelDetails = new CompletionItemLabelDetails();
            labelDetails.setDetail(completion.getLabelDetail());
            labelDetails.setDescription(completion.getLabelDescription());
            item.setLabelDetails(labelDetails);
        }
        if (completion.getKind() != null) {
            item.setKind(CompletionItemKind.valueOf(completion.getKind().name()));
        }
        if (completion.getTags() != null) {
            item.setTags(completion.getTags().stream().map(tag -> CompletionItemTag.valueOf(tag.name())).collect(Collectors.toList()));
        }
        if (completion.getDetail() != null && completion.getDetail().isDone()) {
            item.setDetail(completion.getDetail().getNow(null));
        }
        if (completion.getDocumentation() != null && completion.getDocumentation().isDone()) {
            String documentation = completion.getDocumentation().getNow(null);
            if (documentation != null) {
                MarkupContent markup = new MarkupContent();
                markup.setKind("html");
                markup.setValue(documentation);
                item.setDocumentation(markup);
            }
        }
        if (completion.isPreselect()) {
            item.setPreselect(true);
        }
        item.setSortText(completion.getSortText());
        item.setFilterText(completion.getFilterText());
        item.setInsertText(completion.getInsertText());
        if (completion.getInsertTextFormat() != null) {
            item.setInsertTextFormat(InsertTextFormat.valueOf(completion.getInsertTextFormat().name()));
        }
        org.netbeans.api.lsp.TextEdit edit = completion.getTextEdit();
        if (edit != null) {
            item.setTextEdit(Either.forLeft(new TextEdit(new Range(createPosition(doc, edit.getStartOffset()), createPosition(doc, edit.getEndOffset())), edit.getNewText())));
        }
        org.netbeans.api.lsp.Command command = completion.getCommand();
        if (command != null) {
            item.setCommand(new Command(command.getTitle(), command.getCommand(), command.getArguments()));
        }
        if (completion.getAdditionalTextEdits() != null && completion.getAdditionalTextEdits().isDone()) {
            List<org.netbeans.api.lsp.TextEdit> additionalTextEdits = completion.getAdditionalTextEdits().getNow(null);
            if (additionalTextEdits != null && !additionalTextEdits.isEmpty()) {
                item.setAdditionalTextEdits(additionalTextEdits.stream().map(ed -> {
                    return new TextEdit(new Range(createPosition(doc, ed.getStartOffset()), createPosition(doc, ed.getEndOffset())), ed.getNewText());
                }).collect(Collectors.toList()));
            }
        }
        if (completion.getCommitCharacters() != null) {
            item.setCommitCharacters(completion.getCommitCharacters().stream().map(ch -> ch.toString()).collect(Collectors.toList()));
        }
        item.setData(new CompletionResolutionData(doc, completion));
        return item;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(DidChangeConfigurationParams params) {
            }
            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            }
        };
    }
    
    public static Position createPosition(Document doc, int offset) {
        try {
            return new Position(LineDocumentUtils.getLineIndex((LineDocument) doc, offset),
                    offset - LineDocumentUtils.getLineStart((LineDocument) doc, offset));
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    record CompletionResolutionData(Document doc, Completion completion) {}
}
