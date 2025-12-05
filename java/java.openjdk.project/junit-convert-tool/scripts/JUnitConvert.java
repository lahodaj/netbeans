
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class JUnitConvert {

    public static void main(String... args) throws Exception {
        if (args.length == 1) {
            if ("--help".equals(args[0])) {
                help();
                return ;
            }
            Path directoryToConvert = Paths.get(args[0]).toAbsolutePath();
            if (Files.exists(directoryToConvert)) {
                Path jdkRootSearch = directoryToConvert;
                while (jdkRootSearch != null) {
                    if (Files.exists(jdkRootSearch.resolve("src/java.base/share/classes/java/lang/Object.java"))) {
                        doConvert(jdkRootSearch, directoryToConvert);
                        return ;
                    }

                    if (Files.exists(jdkRootSearch.resolve("open/src/java.base/share/classes/java/lang/Object.java"))) {
                        doConvert(jdkRootSearch.resolve("open"), directoryToConvert);
                        return ;
                    }

                    jdkRootSearch = jdkRootSearch.getParent();
                }
                System.err.println("Cannot find the JDK root starting from: " + directoryToConvert);
            } else {
                System.err.println("Cannot find directory to convert: " + directoryToConvert);
            }
        } else {
            System.err.println("Expected a test directory to convert as a parameter.");
        }

        help();
    }

    private static void help() {
        System.err.println("Usage:");
        System.err.println("java JUnitConvert <directory-with-tests-to-convert>");
    }

    private static void doConvert(Path jdkRoot, Path directoryToConvert) throws Exception {
        Path scratchUserDir = Files.createTempDirectory("junit-conversion");
        Path scratchCacheDir = Files.createTempDirectory("junit-conversion");
        Path thisSource = Paths.get(JUnitConvert.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        new ProcessBuilder(thisSource.getParent().resolve("bin").resolve("junit_convert_tool").toString(),
                           "--userdir", scratchUserDir.toString(),
                           "--cachedir", scratchCacheDir.toString(),
                           "--jdkhome", System.getProperty("java.home"),
                           "--java-hints-hack-open-project=" + List.of("java.base","java.compiler","java.xml").stream().map(project -> jdkRoot.resolve("src").resolve(project).toString()).collect(Collectors.joining(",")),
                           "--java-hints-run-directories=" + directoryToConvert.toString(),
                           "--java-hints-run-apply=openjdk.junit.convert.TestNG2JUnit",
                           "--java-hints-shutdown-when-done",
                           "-J--add-opens=java.base/java.net=ALL-UNNAMED",
                           "-J--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                           "-J--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED",
                           "-J--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                           "-J-Dsun.misc.unsafe.memory.access=allow",
                           "-J--enable-native-access=ALL-UNNAMED",
                           "--nogui",
                           "--nosplash",
                           "-J-Dnetbeans.logger.console=false")
                .inheritIO()
                .start()
                .waitFor();
    }
}