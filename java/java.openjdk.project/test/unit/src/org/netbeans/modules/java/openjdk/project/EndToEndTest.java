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
import java.net.URL;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import junit.framework.Test;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ClassIndex.SearchScope;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.JavaSource.Phase;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.junit.NbModuleSuite;
import org.netbeans.junit.NbTestCase;
import org.netbeans.modules.parsing.spi.indexing.ErrorsCache;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class EndToEndTest extends NbTestCase {

    public EndToEndTest(String name) {
        super(name);
    }

    public void testCanParseJDKSources() throws Exception {
        String jdkSourcesPath = System.getProperty("test.arg.jdk.path");

        assertNotNull(jdkSourcesPath);

        FileObject jdkRoot = FileUtil.toFileObject(new File(jdkSourcesPath));
        Project[] projects = new Project[] {
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/java.base")),
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/java.compiler")),
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/java.desktop")),
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/java.instrument")),
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/java.management")),
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/jdk.compiler")),
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/jdk.jfr")),
            FileOwnerQuery.getOwner(jdkRoot.getFileObject("src/jdk.jshell")),
        };

        OpenProjects.getDefault().open(projects, false);
        OpenProjects.getDefault().openProjects().get();
        
        SourceUtils.waitScanFinished();

        Set<FileObject> roots = new HashSet<>();

        for (Project p : projects) {
            for (SourceGroup sg : ProjectUtils.getSources(p).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
                roots.add(sg.getRootFolder());
            }
        }

        Set<? extends URL> filesInError = roots.stream()
                                               .flatMap(root -> readFilesInError(root).stream())
                                               .collect(Collectors.toSet());

        assertEquals("Unexpected erroneous files.", Set.of(), filesInError);

        for (String test : new String[] {"test/langtools/jdk/jshell/DyingRemoteAgent.java",
                                         "test/langtools/jdk/jshell/ClassPathTest.java",
                                         "test/langtools/tools/javac/Diagnostics/compressed/IncompatibleArgTypesInLambda.java",
                                         "test/jdk/java/foreign/TestAccessModes.java",
                                         "test/jdk/java/lang/LazyConstant/TrustedFieldTypeTest.java",
                                         "test/jdk/java/lang/ProcessHandle/InfoTest.java"}) {
            FileObject test2Try = jdkRoot.getFileObject(test);

            JavaSource.forFileObject(test2Try)
                    .runUserActionTask(cc -> {
                        cc.toPhase(Phase.RESOLVED);
                          List<Diagnostic> errors = cc.getDiagnostics()
                                                      .stream()
                                                      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                                                      .toList();
                        assertTrue(cc.getDiagnostics().toString(), errors.isEmpty() );
                        Set<String> testFQNs = cc.getClasspathInfo().getClassIndex().getDeclaredTypes("Test", ClassIndex.NameKind.PREFIX, EnumSet.allOf(SearchScope.class)).stream().map(eh -> eh.getQualifiedName().toString()).collect(Collectors.toSet());
                        assertTrue(testFQNs.toString(), testFQNs.contains("org.testng.annotations.Test"));
                    }, true);
        }

//        //investigate many tests:
//        FileObject jdkTests = jdkRoot.getFileObject("test/jdk/java/lang");
//        Enumeration<? extends FileObject> en = jdkTests.getChildren(true);
//        while (en.hasMoreElements()) {
//            FileObject f = en.nextElement();
//            if (!f.isData() || !"java".equals(f.getExt()) || !f.asText().contains("@test")) {
//                continue;
//            }
//            JavaSource.forFileObject(f)
//                      .runUserActionTask(cc -> {
//                          cc.toPhase(Phase.RESOLVED);
//                          List<Diagnostic> errors = cc.getDiagnostics()
//                                                      .stream()
//                                                      .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
//                                                      .toList();
//                          if (!errors.isEmpty()) {
//                              System.err.println("file with errors: " + cc.getFileObject());
////                              System.err.println(cc.getDiagnostics().toString());
//                          }
//                      }, true);
//        }
    }

    private static Collection<? extends URL> readFilesInError(FileObject root) {
        try {
            return ErrorsCache.getAllFilesInError(root.toURL());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void noop() {}

    public static Test suite() {
        String jdkSourcesPath = System.getProperty("test.arg.jdk.path");

        if (jdkSourcesPath == null) {
            //pass vacuously:
            return new EndToEndTest("noop");
        }

        return NbModuleSuite.create(NbModuleSuite.createConfiguration(EndToEndTest.class).enableModules(".*", ".*").gui(false));
    }
}
