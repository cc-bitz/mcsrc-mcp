package io.github.ccbitz.mcsrcmcp.decompiler;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.OptionParser;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.TextTokenVisitor;
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.util.token.TextRange;

/**
 * Decompiles a whole jar the way mache's decompile step does - Vineflower's console decompiler with
 * mache's options and the Minecraft libraries - and additionally records Vineflower's text tokens for
 * every class, which the console can't do. The server applies Paper's source patches to this output,
 * and those patches only apply to a whole-jar decompile: Vineflower qualifies a nested class by its
 * outer class only when the outer class is part of the same run.
 *
 * <p>Usage: {@code TreeDecompiler <outDir> <inputJar> <librariesFile> [--option=value ...]}, the
 * libraries file holding one jar path per line. For each outer class it writes
 * {@code <outDir>/<internal/Name>.java} and {@code <outDir>/<internal/Name>.tokens}, one token per
 * line: {@code start, length, kind, className, memberName, memberDescriptor, declaration(1/0)},
 * tab-separated, "-" for an absent member.
 */
public final class TreeDecompiler extends ConsoleDecompiler {

    // TextTokenVisitor.PROPERTY_NAME is private; DecompileService in the server seeds the same key.
    private static final String TEXT_TOKEN_VISITOR_PROPERTY = "text_token_visitor";

    private final Path outDir;
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final Map<String, List<String>> tokensByContent;

    private TreeDecompiler(Path outDir, Map<String, Object> options, Map<String, List<String>> tokensByContent) {
        // LEGACY makes this object the result saver; FOLDER and FILE build a separate one, which
        // would bypass the overrides below and write files of its own.
        super(outDir.toFile(), options, new PrintStreamLogger(System.out), SaveType.LEGACY_CONSOLEDECOMPILER);
        this.outDir = outDir;
        this.tokensByContent = tokensByContent;
    }

    // Hides ConsoleDecompiler.main, which declares no checked exceptions - hence the unchecked wrap.
    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: TreeDecompiler <outDir> <inputJar> <librariesFile> [--option=value ...]");
        }
        Path outDir = Path.of(args[0]);
        Path input = Path.of(args[1]);
        List<String> libraries = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8);

        Map<String, Object> options = new HashMap<>(consoleDefaults());
        for (int i = 3; i < args.length; i++) {
            if (!OptionParser.parse(args[i], options)) {
                throw new IllegalArgumentException("not a Vineflower option: " + args[i]);
            }
        }

        // A whole-jar decompile writes classes from several threads at once, so each class write gets
        // a collector of its own rather than one shared instance.
        Map<String, List<String>> tokensByContent = new ConcurrentHashMap<>();
        List<TextTokenVisitor.Factory> factories = new ArrayList<>();
        factories.add(next -> new Collector(next, tokensByContent));
        options.put(TEXT_TOKEN_VISITOR_PROPERTY, factories);

        Files.createDirectories(outDir);
        try (TreeDecompiler decompiler = new TreeDecompiler(outDir, options, tokensByContent)) {
            for (String library : libraries) {
                if (!library.isBlank()) decompiler.addLibrary(new File(library.trim()));
            }
            decompiler.addSource(input.toFile());
            decompiler.decompileContext();
            decompiler.writeAll();
        }
    }

    // The console starts from these (include-runtime=current in 1.12); read rather than copied, so a
    // Vineflower whose defaults differ still decompiles the way its own console would.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> consoleDefaults() {
        try {
            Field field = ConsoleDecompiler.class.getDeclaredField("CONSOLE_DEFAULT_OPTIONS");
            field.setAccessible(true);
            return (Map<String, Object>) field.get(null);
        } catch (ReflectiveOperationException e) {
            return Map.of("include-runtime", "current");
        }
    }

    // Written after the run rather than as each class arrives: a class's content reaches the saver
    // and its tokens reach the collector from the same write, in an order Vineflower doesn't promise.
    private void writeAll() {
        sources.forEach((internalName, content) -> {
            Path java = outDir.resolve(internalName + ".java");
            Path tokens = outDir.resolve(internalName + ".tokens");
            try {
                Files.createDirectories(java.getParent());
                Files.writeString(java, content, StandardCharsets.UTF_8);
                Files.write(tokens, tokensByContent.getOrDefault(content, List.of()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
        if (content != null) sources.put(qualifiedName, content);
    }

    @Override
    public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
        if (content != null) sources.put(qualifiedName, content);
    }

    @Override
    public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content, int[] mapping) {
        if (content != null) sources.put(qualifiedName, content);
    }

    // Nothing but sources is written: no archive, no directories, no copied resources.
    @Override
    public void createArchive(String path, String archiveName, Manifest manifest) {}

    @Override
    public void saveDirEntry(String path, String archiveName, String entryName) {}

    @Override
    public void copyEntry(String source, String path, String archiveName, String entry) {}

    @Override
    public void closeArchive(String path, String archiveName) {}

    @Override
    public void saveFolder(String path) {}

    @Override
    public void copyFile(String source, String path, String entryName) {}

    private static final class Collector extends TextTokenVisitor {

        private final Map<String, List<String>> tokensByContent;
        private String content;
        private List<String> tokens = new ArrayList<>();

        Collector(TextTokenVisitor next, Map<String, List<String>> tokensByContent) {
            super(next);
            this.tokensByContent = tokensByContent;
        }

        @Override
        public void start(String content) {
            this.content = content;
            this.tokens = new ArrayList<>();
            super.start(content);
        }

        @Override
        public void visitClass(TextRange range, boolean declaration, String className) {
            add(range, "class", className, "-", "-", declaration);
            super.visitClass(range, declaration, className);
        }

        @Override
        public void visitField(TextRange range, boolean declaration, String className, String name, FieldDescriptor descriptor) {
            add(range, "field", className, name, descriptor.descriptorString, declaration);
            super.visitField(range, declaration, className, name, descriptor);
        }

        // MethodDescriptor's toString is its descriptor; the server's DecompileServiceTest pins that.
        @Override
        public void visitMethod(TextRange range, boolean declaration, String className, String name, MethodDescriptor descriptor) {
            add(range, "method", className, name, descriptor.toString(), declaration);
            super.visitMethod(range, declaration, className, name, descriptor);
        }

        @Override
        public void visitParameter(
            TextRange range,
            boolean declaration,
            String className,
            String methodName,
            MethodDescriptor methodDescriptor,
            int index,
            String name
        ) {
            add(range, "parameter", className, "-", "-", declaration);
            super.visitParameter(range, declaration, className, methodName, methodDescriptor, index, name);
        }

        @Override
        public void visitLocal(
            TextRange range,
            boolean declaration,
            String className,
            String methodName,
            MethodDescriptor methodDescriptor,
            int index,
            String name
        ) {
            add(range, "local", className, "-", "-", declaration);
            super.visitLocal(range, declaration, className, methodName, methodDescriptor, index, name);
        }

        @Override
        public void end() {
            if (content != null) tokensByContent.put(content, tokens);
            content = null;
            super.end();
        }

        private void add(TextRange range, String kind, String className, String name, String descriptor, boolean declaration) {
            tokens.add(range.start + "\t" + range.length + "\t" + kind + "\t" + className + "\t" + name + "\t" + descriptor + "\t" + (declaration ? "1" : "0"));
        }

    }

}
