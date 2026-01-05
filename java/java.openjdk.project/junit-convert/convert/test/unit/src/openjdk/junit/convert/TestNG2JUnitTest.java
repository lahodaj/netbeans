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

public class TestNG2JUnitTest {

    @Test
    public void testAssertEqualsConversion1() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("""
                       package test;
                       import org.testng.Assert;
                       public class Test {
                           public static void main(String[] args) {
                               Object v1 = "";
                               Object v2 = "";
                               Assert.assertEquals(v1, v2);
                           }
                       }
                       """)
                .run(TestNG2JUnit.class)
                .findWarning("6:15-6:27:verifier:" + Bundle.ERR_TestNG2JUnit())
                .applyFix()
                .assertOutput("""
                              package test;
                              import org.junit.jupiter.api.Assertions;
                              import org.testng.Assert;
                              public class Test {
                                  public static void main(String[] args) {
                                      Object v1 = "";
                                      Object v2 = "";
                                      Assertions.assertEquals(v2, v1);
                                  }
                              }
                              """);
    }

    @Test
    public void testAssertEqualsConversionAlreadyFlipped1() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("""
                       package test;
                       import org.testng.Assert;
                       public class Test {
                           public static void main(String[] args) {
                               Object expected = "";
                               Object v2 = "";
                               Assert.assertEquals(expected, v2);
                           }
                       }
                       """)
                .run(TestNG2JUnit.class)
                .findWarning("6:15-6:27:verifier:" + Bundle.ERR_TestNG2JUnit())
                .applyFix()
                .assertOutput("""
                              package test;
                              import org.junit.jupiter.api.Assertions;
                              import org.testng.Assert;
                              public class Test {
                                  public static void main(String[] args) {
                                      Object expected = "";
                                      Object v2 = "";
                                      Assertions.assertEquals(expected, v2);
                                  }
                              }
                              """);
    }

    @Test
    public void testAssertEqualsConversionAlreadyFlipped2() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("""
                       package test;
                       import org.testng.Assert;
                       public class Test {
                           public static void main(String[] args) {
                               Object v2 = "";
                               Assert.assertEquals("", v2);
                           }
                       }
                       """)
                .run(TestNG2JUnit.class)
                .findWarning("5:15-5:27:verifier:" + Bundle.ERR_TestNG2JUnit())
                .applyFix()
                .assertOutput("""
                              package test;
                              import org.junit.jupiter.api.Assertions;
                              import org.testng.Assert;
                              public class Test {
                                  public static void main(String[] args) {
                                      Object v2 = "";
                                      Assertions.assertEquals("", v2);
                                  }
                              }
                              """);
    }

    @Test
    public void testAssertEqualsConversionAlreadyFlipped3() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("""
                       package test;
                       import org.testng.Assert;
                       public class Test {
                           public static void main(String[] args) {
                               Object v2 = 0;
                               Assert.assertEquals((Integer) 0, v2);
                           }
                       }
                       """)
                .run(TestNG2JUnit.class)
                .findWarning("5:15-5:27:verifier:" + Bundle.ERR_TestNG2JUnit())
                .applyFix()
                .assertOutput("""
                              package test;
                              import org.junit.jupiter.api.Assertions;
                              import org.testng.Assert;
                              public class Test {
                                  public static void main(String[] args) {
                                      Object v2 = 0;
                                      Assertions.assertEquals((Integer) 0, v2);
                                  }
                              }
                              """);
    }

    @Test
    public void testRemoveStaticModifierFromTestMethod1() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.testng.annotations.Test;

                       @Test
                       public class A {
                           public static void toRun() {}
                           private void toRun2() {}
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import org.junit.jupiter.api.Test;

                              public class A {
                                  @Test
                                  public void toRun() {}
                                  private void toRun2() {}
                              }
                              """);
    }

    @Test
    public void testRemoveStaticModifierFromTestMethod2() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.testng.annotations.Test;

                       public class A {
                           @Test
                           public static void toRun() {}
                           @Test
                           private void toRun2() {}
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import org.junit.jupiter.api.Test;

                              public class A {
                                  @Test
                                  public void toRun() {}
                                  @Test
                                  void toRun2() {}
                              }
                              """);
    }

    @Test
    public void testRemoveStaticModifierFromTestMethod3() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.testng.annotations.DataProvider;
                       import org.testng.annotations.Test;

                       public class A {
                           @Test(dataProvider="data")
                           private static void toRun(String value) {}
                           @DataProvider(name = "data")
                           Object[][] data() { return null; }
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import org.junit.jupiter.api.TestInstance;
                              import org.junit.jupiter.params.ParameterizedTest;
                              import org.junit.jupiter.params.provider.MethodSource;

                              @TestInstance(TestInstance.Lifecycle.PER_CLASS)
                              public class A {
                                  @ParameterizedTest
                                  @MethodSource("data")
                                  void toRun(String value) {}
                                  Object[][] data() { return null; }
                              }
                              """);
    }

    @Test
    public void testAssertEqualsBothDirections() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("""
                       package test;
                       import org.testng.Assert;
                       public class Test {
                           public static void main(String[] args) {
                               Object v1 = "";
                               Object v2 = "";
                               Assert.assertEquals(v1, v2);
                               Assert.assertEquals(v2, v1);
                           }
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("""
                              package test;
                              import org.junit.jupiter.api.Assertions;
                              public class Test {
                                  public static void main(String[] args) {
                                      Object v1 = "";
                                      Object v2 = "";
                                      Assertions.assertEquals(v1, v2);
                                      Assertions.assertEquals(v2, v1);
                                  }
                              }
                              """);
    }

    @Test
    public void testConvertTimeOuts1() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.testng.annotations.Test;
                       public class A {
                           @Test(timeOut=60_000)
                           public void test() {
                           }
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import org.junit.jupiter.api.Test;
                              import org.junit.jupiter.api.Timeout;
                              public class A {
                                  @Test
                                  @Timeout(60)
                                  public void test() {
                                  }
                              }
                              """);
    }

    @Test
    public void testConvertTimeOuts2() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.testng.annotations.Test;
                       public class A {
                           @Test(timeOut=60_001)
                           public void test() {
                           }
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import java.util.concurrent.TimeUnit;
                              import org.junit.jupiter.api.Test;
                              import org.junit.jupiter.api.Timeout;
                              public class A {
                                  @Test
                                  @Timeout(value = 60_001, unit = TimeUnit.MILLISECONDS)
                                  public void test() {
                                  }
                              }
                              """);
    }

    @Test
    public void testConvertTimeOuts3() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.testng.annotations.Test;
                       public class A {
                           private static final int TIMEOUT = 60_000;
                           @Test(timeOut=TIMEOUT)
                           public void test1() {
                           }
                           @Test(timeOut=2 * TIMEOUT)
                           public void test2() {
                           }
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import java.util.concurrent.TimeUnit;
                              import org.junit.jupiter.api.Test;
                              import org.junit.jupiter.api.Timeout;
                              public class A {
                                  private static final int TIMEOUT = 60_000;
                                  @Test
                                  @Timeout(value = TIMEOUT, unit = TimeUnit.MILLISECONDS)
                                  public void test1() {
                                  }
                                  @Test
                                  @Timeout(value = 2 * TIMEOUT, unit = TimeUnit.MILLISECONDS)
                                  public void test2() {
                                  }
                              }
                              """);
    }

    @Test
    public void testConvertTimeOuts4() throws Exception {
        HintTest.create()
                .classpath(classpath())
                .input("test/A.java",
                       """
                       package test;
                       import org.testng.annotations.Test;
                       public class A {
                           @Test(timeOut=10_000_000_000_000L)
                           public void test() {
                           }
                       }
                       """)
                .runBulk(TestNG2JUnit.class)
                .assertOutput("test/A.java",
                              """
                              package test;
                              import org.junit.jupiter.api.Test;
                              import org.junit.jupiter.api.Timeout;
                              public class A {
                                  @Test
                                  @Timeout(10000000000L)
                                  public void test() {
                                  }
                              }
                              """);
    }

    private static URL[] classpath() {
        return new URL[] {
            FileUtil.getArchiveRoot(org.testng.Assert.class.getProtectionDomain().getCodeSource().getLocation()),
            FileUtil.getArchiveRoot(org.junit.jupiter.api.Assertions.class.getProtectionDomain().getCodeSource().getLocation()),
            FileUtil.getArchiveRoot(org.junit.jupiter.params.ParameterizedTest.class.getProtectionDomain().getCodeSource().getLocation()),
        };
    }
}
