TestNG to JUnit conversion for OpenJDK tests
===

This is a tool to convert (primarily) OpenJDK tests from TestNG to JUnit 5.

To use the tool:
1. have built OpenJDK sources
2. to convert tests in directory $TESTS_TO_CONVERT, do:
```
java JUnitConvert.java $TESTS_TO_CONVERT
```

`java` should be at least JDK 17.
