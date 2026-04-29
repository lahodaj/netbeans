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

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.GlobalPathRegistry;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.project.libraries.Library;
import org.netbeans.api.project.libraries.LibraryManager;
import org.netbeans.modules.java.openjdk.project.JDKProject.Root;
import org.netbeans.modules.java.openjdk.project.JDKProject.RootKind;
import org.netbeans.modules.java.openjdk.project.ModuleDescription.ModuleRepository;
import org.netbeans.modules.parsing.spi.indexing.PathRecognizerRegistration;
import org.netbeans.spi.java.classpath.ClassPathImplementation;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.FilteringPathResourceImplementation;
import org.netbeans.spi.java.classpath.PathResourceImplementation;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.netbeans.spi.project.support.ant.PropertyEvaluator;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;

/**
 *
 * @author lahvac
 */
public class ClassPathProviderImpl implements ClassPathProvider {

    private static final Logger LOG = Logger.getLogger(ClassPathProviderImpl.class.getName());
    private static final String[] JDK_CLASSPATH = new String[] {
        "${outputRoot}/jaxp/dist/lib/classes.jar",
        "${outputRoot}/corba/dist/lib/classes.jar",
    };
    
    private final AtomicBoolean initialScanDone = new AtomicBoolean();
    private final ClassPath bootCP;
    private final ClassPath moduleBootCP;
    private final ClassPath compileCP;
    private final ClassPath modulePathCP;
    private final ClassPath allJDKModulesCP;
    private final ClassPath sourceCP;
    private final ClassPath testsCompileCP;
    private final ClassPath testsRegCP;
    private final ModuleRepository repository;

    public ClassPathProviderImpl(JDKProject project, ModuleRepository repository) {
        bootCP = ClassPath.EMPTY;
        moduleBootCP = ClassPath.EMPTY;
        
        if (project.currentModule != null) {
            Collection<String> dependencies = project.moduleRepository.allDependencies(project.currentModule);
            List<URL> mp = new ArrayList<>();
            List<URL> reg = new ArrayList<>();
            for (ModuleDescription mod : project.moduleRepository.modules) {
                FileObject depFO = project.moduleRepository.findModuleRoot(mod.name);

                if (depFO == null)
                    continue; //!!!

                try {
                    URL fakeTarget = projectDir2FakeTarget(depFO);
                    mp.add(fakeTarget);
                    if (dependencies.contains(mod.name)) {
                        reg.add(fakeTarget);
                    }
                } catch (MalformedURLException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }
            compileCP = ClassPath.EMPTY;
            allJDKModulesCP = ClassPathSupport.createClassPath(mp.toArray(URL[]::new));
            modulePathCP = ClassPathSupport.createClassPath(reg.toArray(URL[]::new));
        } else {
            List<PathResourceImplementation> compileElements = new ArrayList<>();

            for (String cp : JDK_CLASSPATH) {
                compileElements.add(new JarBaseResourceImpl(cp, project.evaluator()));
            }
            
            compileCP = ClassPathSupport.createClassPath(compileElements);
            allJDKModulesCP = ClassPath.EMPTY;
            modulePathCP = ClassPath.EMPTY;
        }

        List<PathResourceImplementation> sourceRoots = new ArrayList<>();
        List<PathResourceImplementation> testsRegRoots = new ArrayList<>();
        
        for (Root root : project.getRoots()) {
            if (root.kind == RootKind.MAIN_SOURCES) {
                sourceRoots.add(new PathResourceImpl(root));
            } else if (root.kind == RootKind.TEST_SOURCES) {
                testsRegRoots.add(new PathResourceImpl(root));
            }
        }
        
        sourceCP = ClassPathSupport.createClassPath(sourceRoots);
        List<URL> testCompileRoots = new ArrayList<>();
        if (project.currentModule == null) {
            try {
                testCompileRoots.add(project.getFakeOutput().toURL());
            } catch (MalformedURLException ex) {
                LOG.log(Level.FINE, null, ex);
            }
        }
        for (String libraryName : TEST_LIBRARIES) {
            Library library = LibraryManager.getDefault().getLibrary(libraryName);
            if (library != null) {
                testCompileRoots.addAll(library.getContent("classpath"));
            }
        }

        testsCompileCP = ClassPathSupport.createClassPath(testCompileRoots.toArray(new URL[0]));
        testsRegCP = ClassPathSupport.createClassPath(testsRegRoots);
        this.repository = repository;
    }

    private static final String[] TEST_LIBRARIES = new String[] {"testng", "junit_4", "junit_5"};

    private static URL projectDir2FakeTarget(FileObject projectDir) throws MalformedURLException {
        return FileUtil.getArchiveRoot(projectDir.toURI().resolve("fake-target.jar").toURL());
    }

    @Override
    public ClassPath findClassPath(FileObject file, String type) {
        if (sourceCP.findOwnerRoot(file) != null) {
            if (repository != null && !repository.isAnyProjectOpened()) {
                //if no project is open, java.base may not be indexed. Fallback on default queries:
                return null;
            }
            if (ClassPath.BOOT.equals(type)) {
                return bootCP;
            } else if (JavaClassPathConstants.MODULE_BOOT_PATH.equals(type)) {
                return moduleBootCP;
            } else if (ClassPath.COMPILE.equals(type)) {
                return compileCP;
            } else if (ClassPath.SOURCE.equals(type)) {
                return sourceCP;
            } else if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
                return modulePathCP;
            }
        } else {
            if (file.isFolder()) return null;

            if (ClassPath.BOOT.equals(type) || JavaClassPathConstants.MODULE_BOOT_PATH.equals(type)) {
                if (initialScanDone.get()) {
                    return ClassPath.EMPTY;
                } else {
                    return null;
                }
            } else if (ClassPath.COMPILE.equals(type)) {
                return testsCompileCP;
            } else if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
                //note some modules may not be indexed, and hence may not be usable - it should be enough to open their projects
                //it would be better to follow the @modules tag (and force indexing):
                return allJDKModulesCP;
            }

        }
        
        return null;
    }

    public ClassPath getSourceCP() {
        return sourceCP;
    }

    private static final boolean REGISTER_TESTS_AS_JAVA = Boolean.getBoolean("jdk.project.ClassPathProviderImpl");
    private static final String TEST_SOURCE = "jdk-project-test-source";

    public void registerClassPaths() {
        GlobalPathRegistry.getDefault().register(ClassPath.BOOT, new ClassPath[] {bootCP});
        GlobalPathRegistry.getDefault().register(ClassPath.COMPILE, new ClassPath[] {compileCP});
        GlobalPathRegistry.getDefault().register(ClassPath.COMPILE, new ClassPath[] {modulePathCP});
        GlobalPathRegistry.getDefault().register(ClassPath.SOURCE, new ClassPath[] {sourceCP});
        GlobalPathRegistry.getDefault().register(ClassPath.COMPILE, new ClassPath[] {testsCompileCP});
        if (REGISTER_TESTS_AS_JAVA)
            GlobalPathRegistry.getDefault().register(ClassPath.SOURCE, new ClassPath[] {testsRegCP});
        GlobalPathRegistry.getDefault().register(TEST_SOURCE, new ClassPath[] {testsRegCP});
        try {
            JavaSource js = JavaSource.create(ClasspathInfo.create(ClassPath.EMPTY, testsCompileCP, ClassPath.EMPTY));
            if (js != null) {
                js.runWhenScanFinished(cc -> initialScanDone.set(true), true);
            }
        } catch (IOException ex) {
            Logger.getLogger(ClassPathProviderImpl.class.getName())
                  .log(Level.FINE, null, ex);
        }
    }
    
    public void unregisterClassPaths() {
        GlobalPathRegistry.getDefault().unregister(ClassPath.BOOT, new ClassPath[] {bootCP});
        GlobalPathRegistry.getDefault().unregister(ClassPath.COMPILE, new ClassPath[] {compileCP});
        GlobalPathRegistry.getDefault().unregister(ClassPath.SOURCE, new ClassPath[] {sourceCP});
        if (REGISTER_TESTS_AS_JAVA)
            GlobalPathRegistry.getDefault().unregister(ClassPath.SOURCE, new ClassPath[] {testsRegCP});
        GlobalPathRegistry.getDefault().unregister(TEST_SOURCE, new ClassPath[] {testsRegCP});
    }

    @PathRecognizerRegistration(sourcePathIds=TEST_SOURCE)
    private static final class PathResourceImpl implements FilteringPathResourceImplementation, ChangeListener {

        private final Root root;
        private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

        public PathResourceImpl(Root root) {
            this.root = root;
            this.root.addChangeListener(this);
        }

        @Override
        public boolean includes(URL rootURL, String resource) {
            return root.excludes == null || !root.excludes.matcher(resource).matches();
        }

        @Override
        public URL[] getRoots() {
            return new URL[] { root.getLocation() };
        }

        @Override
        public ClassPathImplementation getContent() {
            return null;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            pcs.addPropertyChangeListener(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            pcs.removePropertyChangeListener(listener);
        }

        @Override
        public void stateChanged(ChangeEvent e) {
            pcs.firePropertyChange(PROP_ROOTS, null, null);
        }

    }

    private static final class JarBaseResourceImpl implements PathResourceImplementation, PropertyChangeListener {

        private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
        private final String jar;
        private final PropertyEvaluator evaluator;
        private URL location;

        public JarBaseResourceImpl(String jar, PropertyEvaluator evaluator) {
            this.jar = jar;
            this.evaluator = evaluator;
        }

        @Override
        public synchronized URL[] getRoots() {
            if (location == null) {
                location = FileUtil.urlForArchiveOrDir(new File(evaluator.evaluate(jar)));
            }
            return new URL[] { location };
        }

        @Override
        public ClassPathImplementation getContent() {
            return null;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            pcs.addPropertyChangeListener(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            pcs.removePropertyChangeListener(listener);
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            synchronized (this) {
                location = null;
            }
            pcs.firePropertyChange(PROP_ROOTS, null, null);
        }

    }

}
