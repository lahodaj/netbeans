TestNG to JUnit conversion for OpenJDK tests
===

This is a tool to convert (primarily) OpenJDK tests from TestNG to JUnit 5.

To use the tool:
1. have built OpenJDK sources
2. to convert tests, do:
```
java JUnitConvert.java <tests-to-convert>
```

Where `<tests-to-convert>` may be files or directories to convert. If directories
are used, all files in those directories, recursivelly, will be converted.
Multiple files or directories are permitted. Please note that class hierarchies
need to be converted in one pass, so if there's a base class extended by several
subclasses, the base class and all the subclasses should be specified here. Specifying
a directory is often sufficient.

`java` should be at least JDK 17.
