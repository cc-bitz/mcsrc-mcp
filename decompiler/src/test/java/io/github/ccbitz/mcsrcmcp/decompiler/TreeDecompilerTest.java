package io.github.ccbitz.mcsrcmcp.decompiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeDecompilerTest {

    private static final String SOURCE = """
        package demo;

        public class Greeter {
            private final String name;

            public Greeter(String name) {
                this.name = name;
            }

            public String greet() {
                return new Helper().wrap(this.name);
            }

            static class Helper {
                String wrap(String value) {
                    return "<" + value + ">";
                }
            }
        }
        """;

    // A subset of mache's decompilerArgs - the ones that shape what this test looks at.
    private static final List<String> OPTIONS = List.of(
        "--decompile-inner=true",
        "--remove-synthetic=true",
        "--indent-string=    "
    );

    private static Path compileToJar(Path dir) throws IOException {
        Path src = dir.resolve("src/demo/Greeter.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, SOURCE);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int exit = javac.run(null, null, null, "-g", "-d", classes.toString(), src.toString());
        assertEquals(0, exit, "fixture failed to compile");

        Path jar = dir.resolve("input.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static void run(Path dir, Path jar, Path out) throws Exception {
        Path libraries = dir.resolve("libraries.txt");
        Files.writeString(libraries, "");
        String[] args = Stream.concat(
            Stream.of(out.toString(), jar.toString(), libraries.toString()),
            OPTIONS.stream()
        ).toArray(String[]::new);
        TreeDecompiler.main(args);
    }

    @Test
    void writesOneSourcePerOuterClassWithInnerClassesInlined(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out");
        run(dir, compileToJar(dir), out);

        assertTrue(Files.isRegularFile(out.resolve("demo/Greeter.java")));
        assertTrue(Files.isRegularFile(out.resolve("demo/Greeter.tokens")));
        assertFalse(Files.exists(out.resolve("demo/Greeter$Helper.java")));
        assertTrue(Files.readString(out.resolve("demo/Greeter.java")).contains("static class Helper"));
    }

    // Every token's span has to be exactly the name it resolves to, or the server would hand
    // find_declaration offsets that point at the wrong text.
    @Test
    void tokenSpansCoverTheNamesTheyResolve(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out");
        run(dir, compileToJar(dir), out);

        String source = Files.readString(out.resolve("demo/Greeter.java"));
        List<String> tokens = Files.readAllLines(out.resolve("demo/Greeter.tokens"), StandardCharsets.UTF_8);
        assertFalse(tokens.isEmpty());

        boolean sawWrapCall = false;
        boolean sawGreetDeclaration = false;
        for (String line : tokens) {
            String[] f = line.split("\t", -1);
            assertEquals(7, f.length, line);
            int start = Integer.parseInt(f[0]);
            int length = Integer.parseInt(f[1]);
            String text = source.substring(start, start + length);
            String kind = f[2];
            String owner = f[3];
            String name = f[4];
            if (kind.equals("method") && !name.equals("<init>")) assertEquals(name, text, line);
            if (kind.equals("field")) assertEquals(name, text, line);
            if (kind.equals("class")) assertEquals(owner.substring(owner.lastIndexOf('/') + 1).replaceAll(".*\\$", ""), text, line);

            sawWrapCall |= kind.equals("method") && owner.equals("demo/Greeter$Helper") && name.equals("wrap")
                && f[5].equals("(Ljava/lang/String;)Ljava/lang/String;") && f[6].equals("0");
            sawGreetDeclaration |= kind.equals("method") && name.equals("greet") && f[6].equals("1");
        }
        assertTrue(sawWrapCall, "no resolved call token for Helper.wrap");
        assertTrue(sawGreetDeclaration, "no declaration token for greet");
    }

}
