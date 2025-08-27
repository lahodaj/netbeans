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
package org.netbeans.api.java.source.gen;

import java.io.File;
import java.io.IOException;

import com.sun.source.tree.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.Modifier;

import org.netbeans.api.java.source.*;
import static org.netbeans.api.java.source.JavaSource.*;
import org.netbeans.junit.NbTestSuite;

/**
 *
 * @author Pavel Flaska
 */
public class TopLevelTest extends GeneratorTestMDRCompat {
    
    public TopLevelTest(String name) {
        super(name);
    }
    
    public static NbTestSuite suite() {
        NbTestSuite suite = new NbTestSuite();
        suite.addTestSuite(TopLevelTest.class);
//        suite.addTest(new TopLevelTest(""));
        return suite;
    }
    
    public void testAddFirstTopLevel0() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        TestUtilities.copyStringToFile(testFile, 
            "package hierbas.del.litoral;\n" +
            "\n");
        String golden =
            "package hierbas.del.litoral;\n" +
            "\n" +
            "public class Test {\n" +
            "}\n";

        JavaSource src = getJavaSource(testFile);
        Task task = new Task<WorkingCopy>() {

            public void run(WorkingCopy workingCopy) throws IOException {
                workingCopy.toPhase(Phase.RESOLVED);
                CompilationUnitTree cut = workingCopy.getCompilationUnit();
                TreeMaker make = workingCopy.getTreeMaker();
                ClassTree clazz = make.Class(
                    make.Modifiers(Collections.<Modifier>singleton(Modifier.PUBLIC)),
                    "Test",
                    Collections.<TypeParameterTree>emptyList(),
                    null,
                    Collections.<Tree>emptyList(),
                    Collections.<Tree>emptyList()
                );
                workingCopy.rewrite(cut, make.addCompUnitTypeDecl(cut, clazz));
            }
            
        };
        src.runModificationTask(task).commit();
        String res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(golden, res);
    }
    
    public void testAddFirstTopLevel1() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        TestUtilities.copyStringToFile(testFile, 
            "package hierbas.del.litoral;\n");
        String golden =
            "package hierbas.del.litoral;\n" +
            "\n" +
            "public class Test {\n" +
            "}\n";

        JavaSource src = getJavaSource(testFile);
        Task task = new Task<WorkingCopy>() {

            public void run(WorkingCopy workingCopy) throws IOException {
                workingCopy.toPhase(Phase.RESOLVED);
                CompilationUnitTree cut = workingCopy.getCompilationUnit();
                TreeMaker make = workingCopy.getTreeMaker();
                ClassTree clazz = make.Class(
                    make.Modifiers(Collections.<Modifier>singleton(Modifier.PUBLIC)),
                    "Test",
                    Collections.<TypeParameterTree>emptyList(),
                    null,
                    Collections.<Tree>emptyList(),
                    Collections.<Tree>emptyList()
                );
                workingCopy.rewrite(cut, make.addCompUnitTypeDecl(cut, clazz));
            }
            
        };
        src.runModificationTask(task).commit();
        String res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(golden, res);
    }
    
    public void testAddFirstTopLevel2() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        TestUtilities.copyStringToFile(testFile, 
            "package hierbas.del.litoral;");
        String golden =
            "package hierbas.del.litoral;\n" +
            "\n" +
            "public class Test {\n" +
            "}\n";

        JavaSource src = getJavaSource(testFile);
        Task task = new Task<WorkingCopy>() {

            public void run(WorkingCopy workingCopy) throws IOException {
                workingCopy.toPhase(Phase.RESOLVED);
                CompilationUnitTree cut = workingCopy.getCompilationUnit();
                TreeMaker make = workingCopy.getTreeMaker();
                ClassTree clazz = make.Class(
                    make.Modifiers(Collections.<Modifier>singleton(Modifier.PUBLIC)),
                    "Test",
                    Collections.<TypeParameterTree>emptyList(),
                    null,
                    Collections.<Tree>emptyList(),
                    Collections.<Tree>emptyList()
                );
                workingCopy.rewrite(cut, make.addCompUnitTypeDecl(cut, clazz));
            }
            
        };
        src.runModificationTask(task).commit();
        String res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(golden, res);
    }

    public void testCommentsImportsChange() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        TestUtilities.copyStringToFile(testFile,
                """
                /*
                 * 1
                 */
                /*
                 * 2
                 */
                import static java.lang.String.valueOf;

                import java.io.IOException;

                public class Test {
                }
                """);
        String golden =
                """
                /*
                 * 1
                 */
                /*
                 * 3
                 */
                import static java.lang.Integer.valueOf;

                import java.io.IOException;
                import java.util.ArrayList;

                public class Test extends ArrayList {
                }
                """;

        JavaSource src = getJavaSource(testFile);
        Task task = new Task<WorkingCopy>() {

            public void run(WorkingCopy workingCopy) throws IOException {
                workingCopy.toPhase(Phase.RESOLVED);
                CompilationUnitTree cut = workingCopy.getCompilationUnit();
                TreeMaker make = workingCopy.getTreeMaker();
                workingCopy.getTreeUtilities().getComments(cut, true);
                workingCopy.getTreeUtilities().getComments(cut.getImports().get(0), true);
                List<ImportTree> imps = new ArrayList<>();
                imps.addAll(cut.getImports());
                imps.set(0, make.Import(make.MemberSelect(make.Identifier("java.lang.Integer"), "valueOf"), true));
                workingCopy.rewrite(cut,
                                    make.CompilationUnit(cut.getPackage(), imps, cut.getTypeDecls(), cut.getSourceFile()));
                String text = workingCopy.getText();
                workingCopy.rewriteInComment(text.indexOf("2"), 1, "3");
                ClassTree topLevel = (ClassTree) cut.getTypeDecls().get(0);
                workingCopy.rewrite(topLevel, make.setExtends(topLevel, make.QualIdent("java.util.ArrayList")));
            }

        };
        src.runModificationTask(task).commit();
        String res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(golden, res);
    }

    String getGoldenPckg() {
        return "";
    }

    String getSourcePckg() {
        return "";
    }
    
}
