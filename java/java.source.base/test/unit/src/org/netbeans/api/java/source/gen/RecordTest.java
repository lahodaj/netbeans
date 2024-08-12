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

import java.io.*;
import com.sun.source.tree.*;
import com.sun.source.tree.Tree.Kind;
import org.netbeans.api.java.source.*;
import static org.netbeans.api.java.source.JavaSource.*;
import org.netbeans.junit.NbTestSuite;

public class RecordTest extends GeneratorTestMDRCompat {

    private String sourceLevel;

    public RecordTest(String testName) {
        super(testName);
    }
    
    public static NbTestSuite suite() {
        NbTestSuite suite = new NbTestSuite();
        suite.addTestSuite(RecordTest.class);
        return suite;
    }

    public void testRenameComponent() throws Exception {
        testFile = new File(getWorkDir(), "Test.java");
        TestUtilities.copyStringToFile(testFile, 
                """
                package hierbas.del.litoral;
                public record R(String component) {}
                """);
        String golden =
                """
                package hierbas.del.litoral;
                public record R(String newName) {}
                """;

        JavaSource src = getJavaSource(testFile);
        Task<WorkingCopy> task = new Task<WorkingCopy>() {

            public void run(WorkingCopy workingCopy) throws IOException {
                workingCopy.toPhase(Phase.RESOLVED);
                CompilationUnitTree cut = workingCopy.getCompilationUnit();
                TreeMaker make = workingCopy.getTreeMaker();

                Tree recordDecl = cut.getTypeDecls().get(0);
                assertEquals(Kind.RECORD, recordDecl.getKind());
                ClassTree classTree = (ClassTree) recordDecl;
                for (Tree m : classTree.getMembers()) {
                    if (m.getKind() == Kind.VARIABLE) {
                        workingCopy.rewrite(m, make.setLabel(m, "newName"));
                    }
                }
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

    @Override
    String getSourceLevel() {
        return sourceLevel;
    }

}
