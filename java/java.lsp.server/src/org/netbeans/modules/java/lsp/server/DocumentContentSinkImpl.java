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
package org.netbeans.modules.java.lsp.server;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import org.netbeans.spi.lsp.modification.DocumentContentModifier;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.netbeans.modules.editor.NbEditorUtilities;
import org.netbeans.modules.java.lsp.server.protocol.NbCodeLanguageClient;
import org.netbeans.modules.java.lsp.server.protocol.SaveDocumentRequestParams;
import org.netbeans.spi.lsp.modification.DocumentSave;
import org.openide.cookies.SaveCookie;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;

public class DocumentContentSinkImpl implements DocumentContentModifier, DocumentSave {

    @Override
    public void writeNewDocumentContent(Document doc, int replaceOffset, int replaceLength, String newContent) throws BadLocationException {
        LanguageClient client = Lookup.getDefault().lookup(LanguageClient.class);
        FileObject file = NbEditorUtilities.getFileObject(doc);

        if (client == null || file == null) {
            writeToDocument(doc, replaceOffset, replaceLength, newContent);
            return ;
        }

        String uri = Utils.toUri(file);

        //XXX:
        if (uri.startsWith("file:") && !uri.startsWith("file://")) {
            uri = "file://" + uri.substring("file:".length());
        }

        LspServerState state = Lookup.getDefault().lookup(LspServerState.class);

        if (state != null && state.getOpenedDocuments().getDocument(uri) == null) {
            //not opened in the client, write directly to the document
            //TODO: on didOpen, any pre-existing changes in the document should be sent to the client??
            writeToDocument(doc, replaceOffset, replaceLength, newContent);
            return ;
        }

        Position startPos = Utils.createPosition(file, replaceOffset);
        Position endPos = Utils.createPosition(file, replaceOffset + replaceLength);

        CompletableFuture<ApplyWorkspaceEditResponse> result = client.applyEdit(new ApplyWorkspaceEditParams(new WorkspaceEdit(List.of(Either.forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri, -1), List.of(new TextEdit(new Range(startPos, endPos), newContent))))))));

        //TODO: detect if this runs synchronously during message processing, produce warning and do asynchronously!
        try {
            result.get();
        } catch (InterruptedException | ExecutionException ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    private void writeToDocument(Document doc, int replaceOffset, int replaceLength, String newContent) throws BadLocationException {
        if (doc instanceof AbstractDocument) {
            ((AbstractDocument) doc).replace(replaceOffset, replaceLength, newContent, null);
        } else {
            if (replaceLength > 0) {
                doc.remove(replaceOffset, replaceLength);
            }
            if (newContent.length() > 0) {
                doc.insertString(replaceOffset, newContent, null);
            }
        }
    }

    @Override
    public void saveDocument(FileObject file) throws IOException {
        NbCodeLanguageClient client = Lookup.getDefault().lookup(NbCodeLanguageClient.class);

        if (client == null) {
            doSaveUsingCookie(file);
            return ;
        }

        String uri = Utils.toUri(file);

        //XXX:
        if (uri.startsWith("file:") && !uri.startsWith("file://")) {
            uri = "file://" + uri.substring("file:".length());
        }

        LspServerState state = Lookup.getDefault().lookup(LspServerState.class);

        if (state != null && state.getOpenedDocuments().getDocument(uri) == null) {
            doSaveUsingCookie(file);
            return ;
        }

        CompletableFuture<Boolean> result = client.requestDocumentSave(new SaveDocumentRequestParams(List.of(uri)));

        //TODO: detect if this runs synchronously during message processing, produce warning and do asynchronously!
        try {
            result.get();
        } catch (InterruptedException | ExecutionException ex) {
            throw new IOException(ex);
        }
    }

    private void doSaveUsingCookie(FileObject file) throws IOException {
        SaveCookie sc = file.getLookup().lookup(SaveCookie.class);
        if (sc != null) {
            sc.save();
        }
    }
    
}
