import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class BaseConvert {

    private final String toolName;
    private final String hintName;

    protected BaseConvert(String toolName, String hintName) {
        this.toolName = toolName;
        this.hintName = hintName;
    }

    public void main(String... args) throws Exception {
        if (args.length >= 1) {
            if ("--help".equals(args[0])) {
                help();
                return ;
            }
            List<Path> resourcesToConvert = new ArrayList<>();
            Path jdkRoot = null;
            boolean ok = true;
            for (String arg : args) {
                Path resourceToConvert = Paths.get(arg).toAbsolutePath();
                if (!Files.exists(resourceToConvert)) {
                    System.err.println("Cannot find resources to convert: " + resourceToConvert);
                    ok = false;
                } else {
                    Path thisResourceJDKRoot = findJDKFrom(resourceToConvert);
                    if (thisResourceJDKRoot == null) {
                        System.err.println("Cannot find the JDK root starting from: " + resourceToConvert);
                        ok = false;
                    } else if (jdkRoot == null) {
                        jdkRoot = thisResourceJDKRoot;
                    } else if (!jdkRoot.equals(thisResourceJDKRoot)) {
                        System.err.println("Resource: " + resourceToConvert + " has different JDK root than previous resource(s).");
                        System.err.println("Previous JDK root: " + jdkRoot + ", this resource's JDK root: " + thisResourceJDKRoot);
                        ok = false;
                    }
                    resourcesToConvert.add(resourceToConvert);
                }
            }
            if (ok) {
                doConvert(jdkRoot, resourcesToConvert);
            } else {
            }
        } else {
            System.err.println("Expected a test directory to convert as a parameter.");
        }

        help();
    }

    private static Path findJDKFrom(Path resource) {
        Path jdkRootSearch = resource;

        while (jdkRootSearch != null) {
            if (Files.exists(jdkRootSearch.resolve("src/java.base/share/classes/java/lang/Object.java"))) {
                return jdkRootSearch;
            }

            if (Files.exists(jdkRootSearch.resolve("open/src/java.base/share/classes/java/lang/Object.java"))) {
                return jdkRootSearch.resolve("open");
            }

            jdkRootSearch = jdkRootSearch.getParent();
        }

        return null;
    }

    private void help() {
        System.err.println("Usage:");
        System.err.println("java " + toolName + ".java <directory-with-tests-to-convert>");
    }

    private void doConvert(Path jdkRoot, List<Path> resourcesToConvert) throws Exception {
        System.out.println("Starting the backend.");
        Path scratchUserDir = Files.createTempDirectory("junit-conversion");
        Path scratchCacheDir = Files.createTempDirectory("junit-conversion");
        try {
            Path thisSource = Paths.get(BaseConvert.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String suffix = ";".equals(System.getProperty("path.separator")) ? "64.exe" : "";
            new ProcessBuilder(thisSource.getParent().resolve("bin").resolve("junit_convert_tool" + suffix).toString(),
                               "--userdir", scratchUserDir.toString(),
                               "--cachedir", scratchCacheDir.toString(),
                               "--jdkhome", System.getProperty("java.home"),
                               "--java-hints-hack-open-project=" + List.of("java.base","java.compiler","java.xml").stream().map(project -> jdkRoot.resolve("src").resolve(project).toString()).collect(Collectors.joining(",")),
                               "--java-hints-run-directories=" + resourcesToConvert.stream().map(p -> p.toString()).collect(Collectors.joining(",")),
                               "--java-hints-run-apply=" + hintName,
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
        } finally {
            deleteRecursivelly(scratchUserDir);
            deleteRecursivelly(scratchCacheDir);
        }
    }
    private static void deleteRecursivelly(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteRecursivelly(c);
                }
            }
        }
        Files.deleteIfExists(p);
    }
}