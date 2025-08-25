TestNG to JUnit conversion for OpenJDK test
===

This is a tool to convert (primarily) OpenJDK tests from TestNG to JUnit 5.

To use the tool:
1. have a built OpenJDK sources, lets denote the directory as "`$JDK_DIR`"
2. checkout this repository/branch:
```
git clone -b openjdk-testng-junit-fixes-and-hacks https://github.com/lahodaj/netbeans/
```
3. build NetBeans:
```
(cd netbeans; ant -Dcluster.config=java)
```
4. to convert tests in directory $TESTS_TO_CONVERT (note: the path *must* be an absolute path), do:
```
(cd netbeans/java/java.openjdk.project/junit-convert; ant -Dconversion.project=$JDK_DIR/src/java.base,$JDK_DIR/src/java.compiler,$JDK_DIR/src/java.xml -Dconversion.directories=$TESTS_TO_CONVERT run-conversion)
```
