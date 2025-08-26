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
package openjdk.junit.convert;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.netbeans.api.actions.Savable;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.api.sendopts.CommandException;
import org.netbeans.modules.java.hints.providers.spi.HintDescription;
import org.netbeans.modules.java.hints.providers.spi.HintMetadata;
import org.netbeans.modules.java.hints.spiimpl.MessageImpl;
import org.netbeans.modules.java.hints.spiimpl.RulesManager;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.BatchResult;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.Folder;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchUtilities;
import org.netbeans.modules.java.hints.spiimpl.batch.ProgressHandleWrapper;
import org.netbeans.modules.java.hints.spiimpl.batch.Scopes;
import org.netbeans.spi.sendopts.Arg;
import org.netbeans.spi.sendopts.ArgsProcessor;
import org.netbeans.spi.sendopts.Description;
import org.netbeans.spi.sendopts.Env;
import org.openide.*;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;

public class OptionProcessorImpl implements ArgsProcessor {

    private static final RequestProcessor WORKER = new RequestProcessor(OptionProcessorImpl.class.getName(), 1, false, false);

    @Arg(longName="junit-convert-directories")
    @Description(shortDescription="#DESC_JUnitConvertDirectories")
    @Messages("DESC_JUnitConvertDirectories=A comma separated list of directories on which the conversion should run")
    public String convertDirectories;

    @Override
    public void process(Env env) throws CommandException {
        List<Folder> folders = new ArrayList<>();

        WORKER.post(() -> {
            OpenProjects.getDefault().close(OpenProjects.getDefault().getOpenProjects());

            try {
                OpenProjects.getDefault().openProjects().get();
            } catch (ExecutionException | InterruptedException ex) {
                ex.printStackTrace();
            }

            List<Project> projects = new ArrayList<>();
            for (String folder : convertDirectories.split(", *")) {
                FileObject resolvedFolder = FileUtil.toFileObject(new File(folder));
                if (resolvedFolder != null) {
                    folders.add(new Folder(resolvedFolder));
                    while (resolvedFolder != null) {
                        try {
                            FileObject javaBaseCandidate = resolvedFolder.getFileObject("src/java.base");
                            Project base = ProjectManager.getDefault().findProject(javaBaseCandidate);
                            if (base != null) {
                                projects.add(base);

                                FileObject javaCompilerCandidate = resolvedFolder.getFileObject("src/java.compiler");
                                Project compiler = ProjectManager.getDefault().findProject(javaCompilerCandidate);

                                if (compiler != null) {
                                    projects.add(compiler);
                                }
                            }
                        } catch (IOException ex) {
                            Exceptions.printStackTrace(ex);
                        } catch (IllegalArgumentException ex) {
                            Exceptions.printStackTrace(ex);
                        }

                        resolvedFolder = resolvedFolder.getParent();
                    }
                }
            }

            OpenProjects.getDefault().open(projects.toArray(new Project[0]), false);

            try {
                OpenProjects.getDefault().openProjects().get();
            } catch (ExecutionException | InterruptedException ex) {
                ex.printStackTrace();
            }
        });

        String hintsToRun = "openjdk.junit.convert.TestNG2JUnit";

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
            BatchSearch.Scope scope = Scopes.specifiedFoldersScope(folders.toArray(new Folder[0]));
            BatchResult occurrences = BatchSearch.findOccurrences(hints, scope);
            List<MessageImpl> problems = new ArrayList<MessageImpl>();
            ProgressHandleWrapper progress = new ProgressHandleWrapper(new int[] {1});
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
        });

        WORKER.post(() -> LifecycleManager.getDefault().exit());
    }

}
