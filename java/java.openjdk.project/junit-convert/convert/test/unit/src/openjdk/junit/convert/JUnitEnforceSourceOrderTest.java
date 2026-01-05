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

import java.net.URL;
import org.junit.Test;
import org.netbeans.modules.java.hints.test.api.HintTest;
import org.openide.filesystems.FileUtil;

public class JUnitEnforceSourceOrderTest {

    @Test
    public void testInsertOrder() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.junit.jupiter.api.Test;
                       import org.junit.jupiter.params.ParameterizedTest;

                       public class A {
                           @Test
                           public void test1() {}
                           @Test
                           public void test2() {}
                           @Deprecated
                           @Test
                           public void test3() {}
                           @ParameterizedTest
                           public void test4() {}
                       }
                       """)
                .runBulk(JUnitEnforceSourceOrder.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import org.junit.jupiter.api.MethodOrderer;
                              import org.junit.jupiter.api.Order;
                              import org.junit.jupiter.api.Test;
                              import org.junit.jupiter.api.TestMethodOrder;
                              import org.junit.jupiter.params.ParameterizedTest;

                              @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
                              public class A {
                                  @Order(0)
                                  @Test
                                  public void test1() {}
                                  @Order(1)
                                  @Test
                                  public void test2() {}
                                  @Deprecated
                                  @Order(2)
                                  @Test
                                  public void test3() {}
                                  @Order(3)
                                  @ParameterizedTest
                                  public void test4() {}
                              }
                              """);
    }

    private static URL[] classpath() {
        return new URL[] {
            FileUtil.getArchiveRoot(org.junit.jupiter.api.Assertions.class.getProtectionDomain().getCodeSource().getLocation()),
            FileUtil.getArchiveRoot(org.junit.jupiter.params.ParameterizedTest.class.getProtectionDomain().getCodeSource().getLocation()),
        };
    }
}
