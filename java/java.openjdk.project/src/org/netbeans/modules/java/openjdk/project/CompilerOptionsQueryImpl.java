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
package org.netbeans.modules.java.openjdk.project;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.spi.java.queries.CompilerOptionsQueryImplementation;
import org.openide.filesystems.FileChangeAdapter;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.ChangeSupport;
import org.openide.util.Exceptions;
import org.openide.util.RequestProcessor;

public class CompilerOptionsQueryImpl implements CompilerOptionsQueryImplementation {

    private final JDKProject project;
    private final Result result = new ResultImpl();

    public CompilerOptionsQueryImpl(JDKProject project) {
        this.project = project;
    }
    
    @Override
    public Result getOptions(FileObject file) {
        return result;
    }

    private class ResultImpl extends Result {
        private static final RequestProcessor WORKER = new RequestProcessor(ResultImpl.class.getName(), 1, false, false);
        private final ChangeSupport cs = new ChangeSupport(this);
        private List<String> arguments;
        private Set<Runnable> removeListeners = Set.of();
        private final ChangeListener changeListener = new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent ce) {
                changed();
            }
        };
        private final FileChangeAdapter fileChangeListener = new FileChangeAdapter() {
            @Override
            public void fileChanged(FileEvent fe) {
                changed();
            }
            @Override
            public void fileDataCreated(FileEvent fe) {
                changed();
            }
            @Override
            public void fileDeleted(FileEvent fe) {
                changed();
            }
        };


        @Override
        public List<? extends String> getArguments() {
            while (true) {
                synchronized (this) {
                    if (arguments != null) {
                        return arguments;
                    }
                }
                WORKER.post(() -> {
                    computeArguments();
                }).waitFinished();
            }
        }

        private void computeArguments() {
            Set<Runnable> currentRemoteListeners = new HashSet<>();
            List<String> arguments = new ArrayList<>();

            for (String dep : project.moduleRepository.allDependencies(project.currentModule)) {
                FileObject depRoot = project.moduleRepository.findModuleRoot(dep);
                Project depProject = FileOwnerQuery.getOwner(depRoot);
                Sources sources = ProjectUtils.getSources(depProject);

                sources.addChangeListener(changeListener);
                currentRemoteListeners.add(() -> sources.removeChangeListener(changeListener));

                for (SourceGroup sg : sources.getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
                    FileObject moduleInfoExtra = sg.getRootFolder().getFileObject("module-info.java.extra");
                    File rootFile = FileUtil.toFile(sg.getRootFolder());
                    File moduleInfoExtraFile = new File(rootFile, "module-info.java.extra");

                    FileUtil.addFileChangeListener(fileChangeListener, moduleInfoExtraFile);
                    currentRemoteListeners.add(() -> FileUtil.removeFileChangeListener(fileChangeListener, moduleInfoExtraFile));

                    if (moduleInfoExtra != null) {
                        try {
                            String text = moduleInfoExtra.asText();
                            for (Map.Entry<String, List<String>> exports : ModuleDescription.parseExports(text).entrySet()) {
                                arguments.add("--add-exports");
                                arguments.add(dep + "/" + exports.getKey() + "=" + exports.getValue().stream().collect(Collectors.joining(",")));
                            }
                        } catch (IOException ex) {
                            Exceptions.printStackTrace(ex);
                        }
                    }
                }
            }

            synchronized (this) {
                this.arguments = arguments;
                this.removeListeners = currentRemoteListeners;
            }
        }

        @Override
        public void addChangeListener(ChangeListener l) {
            cs.addChangeListener(l);
        }

        @Override
        public void removeChangeListener(ChangeListener l) {
            cs.removeChangeListener(l);
        }

        public void changed() {
            WORKER.post(() -> {
                Set<Runnable> listeners2Remove;

                synchronized (ResultImpl.this) {
                    listeners2Remove = new HashSet<>(removeListeners);
                    arguments = null;
                    removeListeners = Set.of();
                }

                listeners2Remove.forEach(r -> r.run());
            });
        }
    }
    
}
