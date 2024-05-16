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
package org.netbeans.modules.java.file.launcher.queries;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.event.ChangeListener;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.api.java.source.TestUtilities;
import org.netbeans.junit.NbTestCase;
import org.netbeans.modules.java.file.launcher.spi.SingleFileOptionsQueryImplementation;
import org.netbeans.modules.java.file.launcher.spi.SingleFileOptionsQueryImplementation.Result;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.ChangeSupport;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

/**
 *
 * @author lahvac
 */
public class MultiSourceRootProviderTest extends NbTestCase {

    public MultiSourceRootProviderTest(String name) {
        super(name);
    }

    public void testFindPackage() {
        assertEquals("test.pack.nested", MultiSourceRootProvider.findPackage("/*package*/package test/**pack*/\n.pack.//package\nnested;"));
        assertEquals(null, MultiSourceRootProvider.findPackage("/*package pack*/"));
    }

    public void testSourcePathFiltering() throws Exception {
        clearWorkDir();

        FileObject wd = FileUtil.toFileObject(getWorkDir());
        FileObject validTest = FileUtil.createData(wd, "valid/pack/Test1.java");
        FileObject invalidTest1 = FileUtil.createData(wd, "valid/pack/Test2.java");
        FileObject invalidTest2 = FileUtil.createData(wd, "valid/pack/Test3.java");

        TestUtilities.copyStringToFile(validTest, "package valid.pack;");
        TestUtilities.copyStringToFile(invalidTest1, "package invalid.pack;");
        TestUtilities.copyStringToFile(invalidTest2, "package invalid;");

        MultiSourceRootProvider provider = new MultiSourceRootProvider();
        ClassPath valid = provider.findClassPath(validTest, ClassPath.SOURCE);

        assertNotNull(valid);
        assertEquals(1, valid.entries().size());
        assertEquals(wd, valid.getRoots()[0]);

        assertNull(provider.findClassPath(invalidTest1, ClassPath.SOURCE));
        assertNull(provider.findClassPath(invalidTest2, ClassPath.SOURCE));
    }

    public void testRelativePaths() throws Exception {
        clearWorkDir();

        AtomicReference<String> options = new AtomicReference<>();
        AtomicReference<URI> workdir = new AtomicReference<>();
        ChangeSupport cs = new ChangeSupport(this);

        Result result = new Result() {
            @Override
            public String getOptions() {
                return options.get();
            }
            @Override
            public URI getWorkDirectory() {
                return workdir.get();
            }
            @Override
            public void addChangeListener(ChangeListener l) {
                cs.addChangeListener(l);
            }
            @Override
            public void removeChangeListener(ChangeListener l) {
                cs.removeChangeListener(l);
            }
        };

        SingleFileOptionsQueryImplementation queryImpl = file -> result;

        Lookups.executeWith(new ProxyLookup(Lookups.fixed(queryImpl), Lookups.exclude(Lookup.getDefault(), SingleFileOptionsQueryImplementation.class)), () -> {
            try {
                FileObject wd = FileUtil.toFileObject(getWorkDir());
                FileObject test = FileUtil.createData(wd, "src/pack/Test1.java");
                FileObject libJar = FileUtil.createData(wd, "libs/lib.jar");
                FileObject other = FileUtil.createFolder(wd, "other");
                FileObject otherLibJar = FileUtil.createData(other, "libs/lib.jar");
                FileObject otherLib2Jar = FileUtil.createData(other, "libs/lib2.jar");

                TestUtilities.copyStringToFile(test, "package pack;");

                options.set("--class-path libs/lib.jar");
                workdir.set(wd.toURI());

                MultiSourceRootProvider provider = new MultiSourceRootProvider();
                ClassPath compileCP = provider.findClassPath(test, ClassPath.COMPILE);
                AtomicInteger changeCount = new AtomicInteger();

                compileCP.addPropertyChangeListener(evt -> {
                    if (ClassPath.PROP_ENTRIES.equals(evt.getPropertyName())) {
                        changeCount.incrementAndGet();
                    }
                });
                assertEquals(FileUtil.toFile(libJar).getAbsolutePath(), compileCP.toString());

                workdir.set(other.toURI());
                cs.fireChange();

                assertEquals(1, changeCount.get());

                assertEquals(FileUtil.toFile(otherLibJar).getAbsolutePath(), compileCP.toString());

                options.set("--class-path libs/lib2.jar");
                cs.fireChange();

                assertEquals(2, changeCount.get());

                assertEquals(FileUtil.toFile(otherLib2Jar).getAbsolutePath(), compileCP.toString());
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    public void testExpandModularDir() throws Exception {
        clearWorkDir();

        AtomicReference<String> options = new AtomicReference<>();
        URI workDir = FileUtil.toFileObject(getWorkDir()).toURI();
        ChangeSupport cs = new ChangeSupport(this);

        Result result = new Result() {
            @Override
            public String getOptions() {
                return options.get();
            }
            @Override
            public URI getWorkDirectory() {
                return workDir;
            }
            @Override
            public void addChangeListener(ChangeListener l) {
                cs.addChangeListener(l);
            }
            @Override
            public void removeChangeListener(ChangeListener l) {
                cs.removeChangeListener(l);
            }
        };

        SingleFileOptionsQueryImplementation queryImpl = file -> result;

        Lookups.executeWith(new ProxyLookup(Lookups.fixed(queryImpl), Lookups.exclude(Lookup.getDefault(), SingleFileOptionsQueryImplementation.class)), () -> {
            try {
                FileObject wd = FileUtil.toFileObject(getWorkDir());
                FileObject test = FileUtil.createData(wd, "src/pack/Test1.java");
                FileObject libsDir = FileUtil.createFolder(wd, "libs");
                FileObject lib1Jar = FileUtil.createData(libsDir, "lib1.jar");
                FileObject lib2Jar = FileUtil.createData(libsDir, "lib2.jar");
                FileObject lib3Dir = FileUtil.createFolder(libsDir, "lib3");

                FileUtil.createData(lib3Dir, "module-info.class");

                TestUtilities.copyStringToFile(test, "package pack;");

                options.set("--module-path " + FileUtil.toFile(libsDir).getAbsolutePath());

                MultiSourceRootProvider provider = new MultiSourceRootProvider();
                ClassPath moduleCP = provider.findClassPath(test, JavaClassPathConstants.MODULE_COMPILE_PATH);
                ClassPath compileCP = provider.findClassPath(test, ClassPath.COMPILE);

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir)),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir)),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                FileObject lib4Jar = FileUtil.createData(libsDir, "lib4.jar");

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar))),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar))),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                options.set("--module-path " + FileUtil.toFile(lib1Jar).getAbsolutePath());

                cs.fireChange();

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar))),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));
                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar))),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                options.set("--module-path " + FileUtil.toFile(lib3Dir).getAbsolutePath());

                cs.fireChange();

                assertEquals(new HashSet<>(Arrays.asList(lib3Dir)),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));
                assertEquals(new HashSet<>(Arrays.asList(lib3Dir)),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                options.set("--module-path " + FileUtil.toFile(libsDir).getAbsolutePath());

                cs.fireChange();

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar))),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar))),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                FileObject lib5Dir = FileUtil.createFolder(libsDir, "lib5Dir");

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar),
                                                         lib5Dir)),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar),
                                                         lib5Dir)),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                lib5Dir.delete();

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar))),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir,
                                                         FileUtil.getArchiveRoot(lib4Jar))),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                lib4Jar.delete();

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir)),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));

                assertEquals(new HashSet<>(Arrays.asList(FileUtil.getArchiveRoot(lib1Jar),
                                                         FileUtil.getArchiveRoot(lib2Jar),
                                                         lib3Dir)),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));

                FileUtil.createData(libsDir, "module-info.class");

                assertEquals(new HashSet<>(Arrays.asList(libsDir)),
                             new HashSet<>(Arrays.asList(moduleCP.getRoots())));
                assertEquals(new HashSet<>(Arrays.asList(libsDir)),
                             new HashSet<>(Arrays.asList(compileCP.getRoots())));
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    static {
        MultiSourceRootProvider.SYNCHRONOUS_UPDATES = true;
    }
}
