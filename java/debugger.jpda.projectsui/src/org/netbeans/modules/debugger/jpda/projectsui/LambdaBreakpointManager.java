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

package org.netbeans.modules.debugger.jpda.projectsui;

import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.netbeans.api.debugger.Breakpoint;
import org.netbeans.api.debugger.DebuggerEngine;
import org.netbeans.api.debugger.DebuggerManager;
import org.netbeans.api.debugger.DebuggerManagerAdapter;
import org.netbeans.api.debugger.LazyDebuggerManagerListener;
import org.netbeans.api.debugger.jpda.FieldBreakpoint;
import org.netbeans.api.debugger.jpda.JPDABreakpoint;
import org.netbeans.api.debugger.jpda.JPDADebugger;
import org.netbeans.api.debugger.jpda.LineBreakpoint;
import org.netbeans.api.debugger.jpda.MethodBreakpoint;
import org.netbeans.api.java.source.CancellableTask;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.JavaSource.Phase;
import org.netbeans.api.java.source.JavaSource.Priority;
import org.netbeans.api.java.source.JavaSourceTaskFactory;
import org.netbeans.api.java.source.support.EditorAwareJavaSourceTaskFactory;
import org.netbeans.spi.debugger.DebuggerServiceRegistration;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.URLMapper;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.lookup.ServiceProvider;

@DebuggerServiceRegistration(types=LazyDebuggerManagerListener.class)
public class LambdaBreakpointManager extends DebuggerManagerAdapter {
    
    private static volatile JPDADebugger currentDebugger = null; //XXX: static???
    private BreakpointAnnotationProvider bap;
    
//    private BreakpointAnnotationProvider getAnnotationProvider() {
//        if (bap == null) {
//            bap = BreakpointAnnotationProvider.getInstance();
//        }
//        return bap;
//    }
    
    @Override
    public String[] getProperties() {
        return new String[] { DebuggerManager.PROP_BREAKPOINTS, DebuggerManager.PROP_DEBUGGER_ENGINES };
    }

    @Override
    public void breakpointAdded(Breakpoint breakpoint) {
        if (breakpoint instanceof LineBreakpoint lb) {
            try {
                URL currentURL = new URL(lb.getURL());
                FileObject fo = URLMapper.findFileObject(currentURL);

                if (fo != null) {
                    FactoryImpl.doRefresh(fo);
                }
            } catch (MalformedURLException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
//        if (BreakpointAnnotationProvider.isAnnotatable(breakpoint)) {
//            JPDABreakpoint b = (JPDABreakpoint) breakpoint;
//            b.addPropertyChangeListener (this);
//            getAnnotationProvider().postAnnotationRefresh(b, false, true);
//            if (b instanceof LineBreakpoint) {
//                LineBreakpoint lb = (LineBreakpoint) b;
//                LineTranslations.getTranslations().registerForLineUpdates(lb);
//            }
//        }
    }

    @Override
    public void breakpointRemoved(Breakpoint breakpoint) {
        //TODO...
//        if (BreakpointAnnotationProvider.isAnnotatable(breakpoint)) {
//            JPDABreakpoint b = (JPDABreakpoint) breakpoint;
//            b.removePropertyChangeListener (this);
//            getAnnotationProvider().postAnnotationRefresh(b, true, false);
//            if (b instanceof LineBreakpoint) {
//                LineBreakpoint lb = (LineBreakpoint) b;
//                LineTranslations.getTranslations().unregisterFromLineUpdates(lb);
//            }
//        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        String propertyName = evt.getPropertyName ();
        if (propertyName == null) {
            return;
        }
        if (DebuggerManager.PROP_CURRENT_ENGINE.equals(propertyName)) {
            setCurrentDebugger(DebuggerManager.getDebuggerManager().getCurrentEngine());
        }
//        if (JPDADebugger.PROP_BREAKPOINTS_ACTIVE.equals(propertyName)) {
//            JPDADebugger debugger = currentDebugger;
//            if (debugger != null) {
//                getAnnotationProvider().setBreakpointsActive(debugger.getBreakpointsActive());
//            }
//        }
        if ( (!LineBreakpoint.PROP_URL.equals (propertyName)) &&
             (!LineBreakpoint.PROP_LINE_NUMBER.equals (propertyName))
        ) {
            return;
        }
        JPDABreakpoint b = (JPDABreakpoint) evt.getSource ();
        DebuggerManager manager = DebuggerManager.getDebuggerManager();
        Breakpoint[] bkpts = manager.getBreakpoints();
        boolean found = false;
        for (int x = 0; x < bkpts.length; x++) {
            if (b == bkpts[x]) {
                found = true;
                break;
            }
        }
        if (!found) {
            // breakpoint has been removed
            return;
        }
//        getAnnotationProvider().postAnnotationRefresh(b, true, true);
    }
    
    private void setCurrentDebugger(DebuggerEngine engine) {
        JPDADebugger oldDebugger = currentDebugger;
        if (oldDebugger != null) {
            oldDebugger.removePropertyChangeListener(JPDADebugger.PROP_BREAKPOINTS_ACTIVE, this);
        }
        boolean active = true;
        JPDADebugger debugger = null;
        if (engine != null) {
            debugger = engine.lookupFirst(null, JPDADebugger.class);
            if (debugger != null) {
                debugger.addPropertyChangeListener(JPDADebugger.PROP_BREAKPOINTS_ACTIVE, this);
                active = debugger.getBreakpointsActive();
            }
        }
        currentDebugger = debugger;
//        getAnnotationProvider().setBreakpointsActive(active);
    }
    
    //TODO: requires dependency on java.source - maybe try to rewrite to Schedulers!
    @ServiceProvider(service=JavaSourceTaskFactory.class)
    public static final class FactoryImpl extends EditorAwareJavaSourceTaskFactory {

        public FactoryImpl() {
            super(Phase.PARSED, Priority.BELOW_NORMAL);
        }

        @Override
        protected CancellableTask<CompilationInfo> createTask(FileObject file) {
            return new CancellableTask<CompilationInfo>() {
                @Override
                public void cancel() {
                }

                @Override
                public void run(CompilationInfo info) throws Exception {
                    String currentFile = info.getFileObject().toURL().toString();
                    Map<Integer, Set<Integer>> lines2LambdaIndexes = new HashMap<>();

                    for (Breakpoint b : DebuggerManager.getDebuggerManager().getBreakpoints()) {
                        if (b instanceof LineBreakpoint lb && currentFile.equals(lb.getURL()) && (b.isEnabled() || lb.getLambdaIndex() >=0)) {
                            lines2LambdaIndexes.computeIfAbsent(lb.getLineNumber(), x -> new HashSet<>())
                                               .add(lb.getLambdaIndex());
                        }
                    }

                    //TODO: span only!
                    new TreePathScanner<Void, Void>() {
//                        private final Set<Integer> seenCodeOnLine = new HashSet<>();
//                        private final Set<Integer> addedLine = new HashSet<>();
//                        private boolean inLambda;
//                        public Void scan(Tree tree, Void v) {
//                            if (tree != null && !inLambda && tree.getKind() != Tree.Kind.COMPILATION_UNIT) {
//                                int startPos = (int) cc.getTrees().getSourcePositions().getStartPosition(getCurrentPath().getCompilationUnit(), tree);
//                                if (startPos != (-1)) {
//                                    Position pos = Utils.createPosition(cc.getCompilationUnit().getLineMap(), startPos);
//
//                                    seenCodeOnLine.add(pos.getLine());
//                                }
//                            }
//                            return super.scan(tree, v);
//                        }
                        int currentLine = -1;
                        int currentLambdaIndex = -1;
                        public Void scan(Tree tree, Void v) {
                            if (tree != null && tree.getKind() != Tree.Kind.COMPILATION_UNIT) {
                                long startPos = info.getTrees().getSourcePositions().getStartPosition(getCurrentPath().getCompilationUnit(), tree);
                                int line = getLine(startPos);

                                if (line != currentLine) {
                                    currentLine = line;
                                    currentLambdaIndex = 0;
                                }
                            }
                            return super.scan(tree, v);
                        }
                        public Void visitLambdaExpression(LambdaExpressionTree tree, Void v) {
                            long startPos = (int) info.getTrees().getSourcePositions().getStartPosition(getCurrentPath().getCompilationUnit(), tree);
                            int line = getLine(startPos);

//                            if (seenCodeOnLine.contains(line) && !addedLine.contains(line)) {
//                                BreakpointLocation l = new BreakpointLocation();
//
//                                l.setLine(line + 1); //XXX: +1 may need to be configuration
//                                l.setColumn(null);
//                                locations.add(l);
//                                addedLine.add(line);
//                            }

                            if (!lines2LambdaIndexes.getOrDefault(line, Collections.emptySet()).contains(currentLambdaIndex)) {
                                LineBreakpoint lb = LineBreakpoint.create(currentFile, line);
                                lb.setLambdaIndex(currentLambdaIndex);
                                lb.disable();
                                DebuggerManager.getDebuggerManager().addBreakpoint(lb);
                            }

                            currentLambdaIndex++;

//                            boolean wasInLambda = inLambda;
//                            try {
//                                inLambda = true;
                                return super.visitLambdaExpression(tree, v);
//                            } finally {
//                                inLambda = wasInLambda;
//                            }
                        }
                        private int getLine(long offset) {
                            return (int) info.getCompilationUnit().getLineMap().getLineNumber(offset);
                        }
                    }.scan(info.getCompilationUnit(), null);
                }
            };
        }

        public static void doRefresh(FileObject file) {
            for (JavaSourceTaskFactory f : Lookup.getDefault().lookupAll(JavaSourceTaskFactory.class)) {
                if (f instanceof FactoryImpl impl) {
                    impl.reschedule(file);
                }
            }
        }
    }

}
