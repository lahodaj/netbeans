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

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.netbeans.api.java.source.Task;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TestUtilities;
import org.netbeans.api.java.source.TreeMaker;
import static org.netbeans.api.java.source.JavaSource.*;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.junit.NbTestSuite;

/**
 * Test adding/removing/modifying permits clause in source.
 * In addition to, tries to work with extends in interfaces.
 */
public class ClassPermitsTest extends GeneratorTestMDRCompat {

    /** Creates a new instance of ClassExtendsTest */
    public ClassPermitsTest(String testName) {
        super(testName);
    }

    public static NbTestSuite suite() {
        NbTestSuite suite = new NbTestSuite();
        suite.addTestSuite(ClassPermitsTest.class);
        return suite;
    }

    public void testModifyExistingPermits1() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        TestUtilities.copyStringToFile(testFile,
            """
            package hierbas.del.litoral;

            public sealed class Test permits Subtype1 {
            }
            final class Subtype1 extends Test {}
            final class Subtype2 extends Test {}
            """
        );
        String golden =
            """
            package hierbas.del.litoral;

            public sealed class Test permits Subtype2 {
            }
            final class Subtype1 extends Test {}
            final class Subtype2 extends Test {}
            """;
        JavaSource src = getJavaSource(testFile);

        Task<WorkingCopy> task = new Task<WorkingCopy>() {

            public void run(WorkingCopy workingCopy) throws IOException {
                workingCopy.toPhase(Phase.RESOLVED);
                CompilationUnitTree cut = workingCopy.getCompilationUnit();
                TreeMaker make = workingCopy.getTreeMaker();
                ClassTree clazz = (ClassTree) cut.getTypeDecls().get(0);
                workingCopy.rewrite(clazz.getPermitsClause().get(0),
                                    make.setLabel(clazz.getPermitsClause().get(0), "Subtype2"));
            }

        };
        src.runModificationTask(task).commit();
        String res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(golden, res);
    }

    public void testModifyExistingPermits2() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        String code = """
                      package hierbas.del.litoral;

                      public sealed class Test permits Subtype2 {
                      }
                      final class Subtype1 extends Test {}
                      final class Subtype2 extends Test {}
                      final class Subtype3 extends Test {}
                      """;
        TestUtilities.copyStringToFile(testFile, code);

        JavaSource src = getJavaSource(testFile);

        Task<WorkingCopy> task;
        String res;

        //add first:
        task = (WorkingCopy workingCopy) -> {
            workingCopy.toPhase(Phase.RESOLVED);
            CompilationUnitTree cut = workingCopy.getCompilationUnit();
            TreeMaker make = workingCopy.getTreeMaker();
            ClassTree clazz = (ClassTree) cut.getTypeDecls().get(0);
            List<Tree> augmentedPermits = new ArrayList<>(clazz.getPermitsClause());
            augmentedPermits.add(0, make.QualIdent("hierbas.del.litoral.Subtype1"));
            ClassTree newClass = make.Class(clazz.getModifiers(), clazz.getSimpleName(), clazz.getTypeParameters(), clazz.getExtendsClause(), clazz.getImplementsClause(), augmentedPermits, clazz.getMembers());
            workingCopy.rewrite(clazz, newClass);
        };
        src.runModificationTask(task).commit();
        res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals("""
                     package hierbas.del.litoral;

                     public sealed class Test permits Subtype1, Subtype2 {
                     }
                     final class Subtype1 extends Test {}
                     final class Subtype2 extends Test {}
                     final class Subtype3 extends Test {}
                     """, res);

        //remove first:
        task = (WorkingCopy workingCopy) -> {
            workingCopy.toPhase(Phase.RESOLVED);
            CompilationUnitTree cut = workingCopy.getCompilationUnit();
            TreeMaker make = workingCopy.getTreeMaker();
            ClassTree clazz = (ClassTree) cut.getTypeDecls().get(0);
            List<Tree> augmentedPermits = new ArrayList<>(clazz.getPermitsClause());
            augmentedPermits.remove(0);
            ClassTree newClass = make.Class(clazz.getModifiers(), clazz.getSimpleName(), clazz.getTypeParameters(), clazz.getExtendsClause(), clazz.getImplementsClause(), augmentedPermits, clazz.getMembers());
            workingCopy.rewrite(clazz, newClass);
        };
        src.runModificationTask(task).commit();
        res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(code, res);

        //add last:
        task = (WorkingCopy workingCopy) -> {
            workingCopy.toPhase(Phase.RESOLVED);
            CompilationUnitTree cut = workingCopy.getCompilationUnit();
            TreeMaker make = workingCopy.getTreeMaker();
            ClassTree clazz = (ClassTree) cut.getTypeDecls().get(0);
            List<Tree> augmentedPermits = new ArrayList<>(clazz.getPermitsClause());
            augmentedPermits.add(make.QualIdent("hierbas.del.litoral.Subtype3"));
            ClassTree newClass = make.Class(clazz.getModifiers(), clazz.getSimpleName(), clazz.getTypeParameters(), clazz.getExtendsClause(), clazz.getImplementsClause(), augmentedPermits, clazz.getMembers());
            workingCopy.rewrite(clazz, newClass);
        };
        src.runModificationTask(task).commit();
        res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals("""
                     package hierbas.del.litoral;

                     public sealed class Test permits Subtype2, Subtype3 {
                     }
                     final class Subtype1 extends Test {}
                     final class Subtype2 extends Test {}
                     final class Subtype3 extends Test {}
                     """, res);

        //remove last:
        task = (WorkingCopy workingCopy) -> {
            workingCopy.toPhase(Phase.RESOLVED);
            CompilationUnitTree cut = workingCopy.getCompilationUnit();
            TreeMaker make = workingCopy.getTreeMaker();
            ClassTree clazz = (ClassTree) cut.getTypeDecls().get(0);
            List<Tree> augmentedPermits = new ArrayList<>(clazz.getPermitsClause());
            augmentedPermits.remove(1);
            ClassTree newClass = make.Class(clazz.getModifiers(), clazz.getSimpleName(), clazz.getTypeParameters(), clazz.getExtendsClause(), clazz.getImplementsClause(), augmentedPermits, clazz.getMembers());
            workingCopy.rewrite(clazz, newClass);
        };
        src.runModificationTask(task).commit();
        res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(code, res);
    }

    public void testIntroduceRemovePermits() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        String code = """
                      package hierbas.del.litoral;

                      public sealed class Test {
                      }
                      final class Subtype1 extends Test {}
                      final class Subtype2 extends Test {}
                      final class Subtype3 extends Test {}
                      """;
        TestUtilities.copyStringToFile(testFile, code);

        JavaSource src = getJavaSource(testFile);

        Task<WorkingCopy> task;
        String res;

        //add first:
        task = (WorkingCopy workingCopy) -> {
            workingCopy.toPhase(Phase.RESOLVED);
            CompilationUnitTree cut = workingCopy.getCompilationUnit();
            TreeMaker make = workingCopy.getTreeMaker();
            ClassTree clazz = (ClassTree) cut.getTypeDecls().get(0);
            List<Tree> augmentedPermits = new ArrayList<>();
            augmentedPermits.add(make.QualIdent("hierbas.del.litoral.Subtype1"));
            ClassTree newClass = make.Class(clazz.getModifiers(), clazz.getSimpleName(), clazz.getTypeParameters(), clazz.getExtendsClause(), clazz.getImplementsClause(), augmentedPermits, clazz.getMembers());
            workingCopy.rewrite(clazz, newClass);
        };
        src.runModificationTask(task).commit();
        res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals("""
                     package hierbas.del.litoral;

                     public sealed class Test permits Subtype1 {
                     }
                     final class Subtype1 extends Test {}
                     final class Subtype2 extends Test {}
                     final class Subtype3 extends Test {}
                     """, res);

        //remove first:
        task = (WorkingCopy workingCopy) -> {
            workingCopy.toPhase(Phase.RESOLVED);
            CompilationUnitTree cut = workingCopy.getCompilationUnit();
            TreeMaker make = workingCopy.getTreeMaker();
            ClassTree clazz = (ClassTree) cut.getTypeDecls().get(0);
            List<Tree> augmentedPermits = new ArrayList<>(clazz.getPermitsClause());
            augmentedPermits.remove(0);
            ClassTree newClass = make.Class(clazz.getModifiers(), clazz.getSimpleName(), clazz.getTypeParameters(), clazz.getExtendsClause(), clazz.getImplementsClause(), augmentedPermits, clazz.getMembers());
            workingCopy.rewrite(clazz, newClass);
        };
        src.runModificationTask(task).commit();
        res = TestUtilities.copyFileToString(testFile);
        //System.err.println(res);
        assertEquals(code, res);
    }

    String getGoldenPckg() {
        return "";
    }

    String getSourcePckg() {
        return "";
    }

    @Override
    String getSourceLevel() {
        return "17";
    }
}
