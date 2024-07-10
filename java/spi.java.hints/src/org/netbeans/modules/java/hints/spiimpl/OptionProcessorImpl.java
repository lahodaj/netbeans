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
package org.netbeans.modules.java.hints.spiimpl;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.netbeans.api.actions.Savable;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.api.sendopts.CommandException;
import org.netbeans.modules.java.hints.providers.spi.HintDescription;
import org.netbeans.modules.java.hints.providers.spi.HintMetadata;
import org.netbeans.modules.java.hints.providers.spi.HintMetadata.Options;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.BatchResult;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.Folder;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.VerifiedSpansCallBack;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchUtilities;
import org.netbeans.modules.java.hints.spiimpl.batch.ProgressHandleWrapper;
import org.netbeans.modules.java.hints.spiimpl.batch.Scopes;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.Severity;
import org.netbeans.spi.java.hints.Hint;
import org.netbeans.spi.sendopts.Arg;
import org.netbeans.spi.sendopts.ArgsProcessor;
import org.netbeans.spi.sendopts.Description;
import org.netbeans.spi.sendopts.Env;
import org.openide.LifecycleManager;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;

public class OptionProcessorImpl implements ArgsProcessor {

    private static final RequestProcessor WORKER = new RequestProcessor(OptionProcessorImpl.class.getName(), 1, false, false);

    @Arg(longName="java-hints-list", defaultValue = "false")
    @Description(shortDescription="#DESC_ListJavaHints")
    @Messages("DESC_ListJavaHints=List Java Hints")
    public boolean listJavaHints;

    @Arg(longName="java-hints-run-print", defaultValue = "")
    @Description(shortDescription="#DESC_RunJavaHintsPrint")
    @Messages("DESC_RunJavaHintsPrint=Run Java Hints - Print Warnings")
    public String runJavaHintsPrint;

    @Arg(longName="java-hints-run-apply", defaultValue = "")
    @Description(shortDescription="#DESC_RunJavaHintsApply")
    @Messages("DESC_RunJavaHintsApply=Run Java Hints - Apply Fixes")
    public String runJavaHintsApply;

    @Arg(longName="java-hints-run-directories", defaultValue = "")
    @Description(shortDescription="#DESC_RunJavaHintsDirectories")
    @Messages("DESC_RunJavaHintsDirectories=A comma separated list of directories on which the hints should run; defaults to all open projects if not specified")
    public String runJavaHintsDirectories;

    @Arg(longName="java-hints-shutdown-when-done", defaultValue = "false")
    @Description(shortDescription="#DESC_ShutdownWhenDone")
    @Messages("DESC_ShutdownWhenDone=Shutdown When Java Hints Run Is Done")
    public boolean shutdownWhenDone;

    @Override
    @Messages("ERR_CannotListAndApplyTogether=Cannot list and apply at the same time")
    public void process(Env env) throws CommandException {
        if (listJavaHints) {
            Map<String, List<HintMetadata>> category2Hints = new TreeMap<>();
            for (HintMetadata metadata : RulesManager.getInstance().readHints(null, Collections.emptyList(), new AtomicBoolean()).keySet()) {
                if (metadata.kind == Hint.Kind.ACTION) {
                    continue;
                }
                if (metadata.options.contains(Options.NO_BATCH)) {
                    continue;
                }
                category2Hints.computeIfAbsent(metadata.category, x -> new ArrayList<>()).add(metadata);
            }
            boolean firstCategory = true;
            PrintStream out = env.getOutputStream();
            for (Map.Entry<String, List<HintMetadata>> e : category2Hints.entrySet()) {
                if (!firstCategory) {
                    out.println();
                    out.println();
                }
                firstCategory = false;

                out.println("==========  Category: '" + e.getKey() + "'  ==========");

                boolean firstHint = true;

                for (HintMetadata metadata : e.getValue()) {
                    if (!firstHint) {
                        out.println("");
                    }
                    firstHint = false;

                    out.println("----------  Hint ID: '" + metadata.id + "'  ----------");
                    out.println(metadata.displayName);
                    out.println(metadata.description);
                    if (metadata.options.contains(Options.QUERY)) {
                        out.println("/query only - no automated fix available/");
                    }
                }
            }
        }
        String hintsToRun;
        boolean apply;
        if (runJavaHintsPrint != null) {
            if (runJavaHintsApply != null) {
                throw new CommandException(1, Bundle.ERR_CannotListAndApplyTogether());
            }
            hintsToRun = runJavaHintsPrint;
            apply = false;
        } else if (runJavaHintsApply != null) {
            hintsToRun = runJavaHintsApply;
            apply = true;
        } else {
            hintsToRun = null;
            apply = false;
        }
        if (hintsToRun != null) {
            WORKER.post(() -> {
                try {
                    OpenProjects.getDefault().openProjects().get();
                } catch (ExecutionException | InterruptedException ex) {
                    ex.printStackTrace();
                }

                Set<String> enabledIds = new HashSet<>(Arrays.asList(hintsToRun.split(", *")));
                List<HintDescription> hints = new ArrayList<>();
                for (Map.Entry<HintMetadata, ? extends Collection<? extends HintDescription>> entry : RulesManager.getInstance().readHints(null, Collections.emptyList(), new AtomicBoolean()).entrySet()) {
                    if (enabledIds.contains(entry.getKey().id)) {
                        hints.addAll(entry.getValue());
                    }
                }
                BatchSearch.Scope scope;
                if (runJavaHintsDirectories != null) {
                    List<Folder> folders = new ArrayList<>();
                    for (String folder : runJavaHintsDirectories.split(", *")) {
                        FileObject resolvedFolder = FileUtil.toFileObject(new File(folder));
                        if (resolvedFolder != null) {
                            folders.add(new Folder(resolvedFolder));
                        }
                    }
                    scope = Scopes.specifiedFoldersScope(folders.toArray(new Folder[0]));
                } else {
                    scope = Scopes.allOpenedProjectsScope();
                }
                BatchResult occurrences = BatchSearch.findOccurrences(hints, scope);
                List<MessageImpl> problems = new ArrayList<MessageImpl>();
                ProgressHandleWrapper progress = new ProgressHandleWrapper(new int[] {1});
                if (apply) {
                    Collection<ModificationResult> diffs = BatchUtilities.applyFixes(occurrences, progress, new AtomicBoolean(), problems);

                    for (ModificationResult mr : diffs) {
                        try {
                            mr.commit();
                            //ensure all modified files are saved:
                            for (FileObject file : mr.getModifiedFileObjects()) {
                                Savable sc = file.getLookup().lookup(Savable.class);
                                if (sc != null) {
                                    sc.save();
                                }
                            }
                        } catch (IOException ex) {
                            Exceptions.printStackTrace(ex);
                        }
                    }
                } else {
                    final Map<String, String> id2DisplayName = computeId2DisplayName(hints);
                    VerifiedSpansCallBack callback = new VerifiedSpansCallBack() {
                        @Override
                        public void groupStarted() {}
                        @Override
                        public boolean spansVerified(CompilationController wc, BatchSearch.Resource r, Collection<? extends ErrorDescription> hints) throws Exception {
                            for (ErrorDescription ed : hints) {
                                print(env.getErrorStream(), ed, id2DisplayName);
                            }

                            return true;
                        }

                        @Override
                        public void groupFinished() {}

                        @Override
                        public void cannotVerifySpan(BatchSearch.Resource r) {
                            //print warnings?
                        }
                    };
                    BatchSearch.getVerifiedSpans(occurrences, progress, callback, problems, new AtomicBoolean());
                }
            });
        }
        if (shutdownWhenDone) {
            WORKER.post(() -> LifecycleManager.getDefault().exit());
        }
    }

    //copied and adjusted from Jackpot 3.0:
    private static void print(PrintStream out, ErrorDescription error, Map<String, String> id2DisplayName) throws IOException {
        int lineNumber = error.getRange().getBegin().getLine();
        String line = error.getFile().asLines().get(lineNumber);
        int column = error.getRange().getBegin().getColumn();
        StringBuilder b = new StringBuilder();

        for (int i = 0; i < column; i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                b.append(line.charAt(i));
            } else {
                b.append(' ');
            }
        }

        b.append('^');

        String idDisplayName = categoryName(error.getId(), id2DisplayName);
        String severity;
        if (error.getSeverity() == Severity.ERROR) {
            severity = "error";
        } else {
            severity = "warning";
        }
        out.println(FileUtil.getFileDisplayName(error.getFile()) + ":" + (lineNumber + 1) + ": " + severity + ": " + idDisplayName + error.getDescription());
        out.println(line);
        out.println(b);
    }

    private static String categoryName(String id, Map<String, String> id2DisplayName) {
        if (id != null && id.startsWith("text/x-java:")) {
            id = id.substring("text/x-java:".length());
        }

        String idDisplayName = id2DisplayName.get(id);

        if (idDisplayName == null) {
            idDisplayName = "unknown";
        }

        for (Entry<String, String> remap : toIdRemap.entrySet()) {
            idDisplayName = idDisplayName.replace(remap.getKey(), remap.getValue());
        }

        idDisplayName = idDisplayName.replaceAll("[^A-Za-z0-9]", "_").replaceAll("_+", "_");

        idDisplayName = "[" + idDisplayName + "] ";

        return idDisplayName;
    }

    private static final Map<String, String> toIdRemap = new HashMap<String, String>() {{
        put("==", "equals");
        put("!=", "not_equals");
    }};

    private static Map<String, String> computeId2DisplayName(Iterable<? extends HintDescription> descs) {
        final Map<String, String> id2DisplayName = new HashMap<>();

        for (HintDescription hd : descs) {
            if (hd.getMetadata() != null) {
                id2DisplayName.put(hd.getMetadata().id, hd.getMetadata().displayName);
            }
        }

        return id2DisplayName;
    }
}
