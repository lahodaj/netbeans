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
package org.netbeans.modules.java.api.common.queries;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.ExportsTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.JavacTask;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.queries.AccessibilityQuery;
import org.netbeans.spi.java.queries.AccessibilityQueryImplementation2;
import org.openide.filesystems.FileChangeAdapter;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.util.ChangeSupport;
import org.openide.util.RequestProcessor;
import org.openide.util.RequestProcessor.Task;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service=AccessibilityQueryImplementation2.class)
public class GenericModuleInfoAccessibilityQuery implements AccessibilityQueryImplementation2 {

    private final Map<ClassPath, ClassPathListener> sourcePath2Listener = new WeakHashMap<>();
    private final Map<FileObject, Result> path2Result = new WeakHashMap<>();

    @Override
    public Result isPubliclyAccessible(FileObject folder) {
        return path2Result.computeIfAbsent(folder, f -> {
            ClassPath sourcePath = ClassPath.getClassPath(folder, ClassPath.SOURCE);
            if (sourcePath == null) {
                return null;
            }
            ClassPathListener cpl = sourcePath2Listener.computeIfAbsent(sourcePath, sp -> new ClassPathListener(sourcePath));
            return new ResultImpl(cpl, sourcePath, folder);
        });
    }

    private static final class ResultImpl implements Result, ChangeListener {

        private final ChangeSupport cs = new ChangeSupport(this);
        private final ClassPathListener listener;
        private final Reference<ClassPath> sourcePath;
        private final Reference<FileObject> folder;

        public ResultImpl(ClassPathListener listener, ClassPath sourcePath, FileObject folder) {
            this.listener = listener;
            this.sourcePath = new WeakReference<>(sourcePath);
            this.folder = new WeakReference<>(folder);
            listener.addChangeListener(this);
        }

        @Override
        public AccessibilityQuery.Accessibility getAccessibility() {
            ClassPath sourcePath = this.sourcePath.get();
            FileObject folder = this.folder.get();
            Set<String> exported = listener.getExportedPackages();

            if (sourcePath == null || folder == null || exported == null) {
                System.err.println("this: " + System.identityHashCode(this) + " no result because: " + sourcePath + ", " + folder + ", " + exported);
                return AccessibilityQuery.Accessibility.UNKNOWN;
            }
            System.err.println("this: " + System.identityHashCode(this) + " will provide result.");
            String packageName = sourcePath.getResourceName(folder).replace('/', '.');
            return exported.contains(packageName) ? AccessibilityQuery.Accessibility.EXPORTED
                                                  : AccessibilityQuery.Accessibility.PRIVATE;
        }

        @Override
        public void addChangeListener(ChangeListener listener) {
            cs.addChangeListener(listener);
        }

        @Override
        public void removeChangeListener(ChangeListener listener) {
            cs.removeChangeListener(listener);
        }

        @Override
        public void stateChanged(ChangeEvent e) {
            cs.fireChange();
        }
        
    }

    private static final class ClassPathListener implements PropertyChangeListener {

        private static final RequestProcessor WORKER = new RequestProcessor(ClassPathListener.class.getName(), 1, false, false);
        private static final int DELAY = 100;
        private final ChangeSupport cs = new ChangeSupport(this);
        private final AtomicReference<Set<String>> exportedPackages = new AtomicReference<>(null);
        private final Reference<ClassPath> sourcePath;
        private final Task parseTask;
        private final Task rootsTask;
        private final FileChangeAdapter folderListener = new FileChangeAdapter() {
            @Override
            public void fileDataCreated(FileEvent fe) {
                if (fe.getFile().getNameExt().equalsIgnoreCase("module-info.java")) {
                    rootsTask.schedule(DELAY);
                }
            }
        };
        private final FileChangeAdapter moduleInfoListener = new FileChangeAdapter() {
            @Override
            public void fileChanged(FileEvent fe) {
                parseTask.schedule(DELAY);
            }
        };

        public ClassPathListener(ClassPath sourcePath) {
            this.sourcePath = new WeakReference<>(sourcePath);
            this.parseTask = WORKER.create(() -> {
                FileObject moduleInfo = sourcePath.findResource("module-info.java");
                Set<String> exported;

                if (moduleInfo != null) {
                    exported = new HashSet<>();

                    try {
                        String code = moduleInfo.asText();
                        JavacTask compilerTask = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, null, null, null, Collections.singleton(new TextJFO(code, moduleInfo.toURI())));
                        CompilationUnitTree cut = compilerTask.parse().iterator().next();
                        ModuleTree mt = cut.getModule();
                        if (mt != null) {
                            for (DirectiveTree dt : mt.getDirectives()) {
                                if (dt.getKind() == Kind.EXPORTS) {
                                    ExportsTree et = (ExportsTree) dt;
                                    if (et.getModuleNames() == null || et.getModuleNames().isEmpty()) {
                                        exported.add(et.getPackageName().toString());
                                    } 
                                } 
                            } 
                        }
                    } catch (IOException ex) {
                        //TODO: log
                        ex.printStackTrace();
                    } 
                } else {
                    exported = null;
                }

                exportedPackages.set(exported);
                cs.fireChange();
            });
            sourcePath.addPropertyChangeListener(this);
            rootsTask = WORKER.create(() -> {
                ClassPath cp = ClassPathListener.this.sourcePath.get();
                for (FileObject root : cp.getRoots()) {
                    root.removeFileChangeListener(folderListener);
                    root.addFileChangeListener(folderListener);
                    FileObject moduleInfo = root.getFileObject("module-info.java");
                    if (moduleInfo != null) {
                        moduleInfo.removeFileChangeListener(moduleInfoListener);
                        moduleInfo.addFileChangeListener(moduleInfoListener);
                    } 
                }
                parseTask.schedule(DELAY);
            });
            rootsTask.schedule(DELAY);
        }

        public Set<String> getExportedPackages() {
            return exportedPackages.get();
        }

        public void addChangeListener(ChangeListener listener) {
            cs.addChangeListener(listener);
        }

        public void removeChangeListener(ChangeListener listener) {
            cs.removeChangeListener(listener);
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            rootsTask.schedule(DELAY);
        }

    }
    private static final class TextJFO extends SimpleJavaFileObject {
        private final String code;

        public TextJFO(String code, URI uri) {
            super(uri, Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return code;
        }

    }
}
