package io.github.ccbitz.mcsrcmcp.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class Indexer {
    private final Map<String, Set<String>> references = new HashMap<>();
    private final Map<String, ClassData> classes = new HashMap<>();
    private final Map<String, MutableMemberData> members = new HashMap<>();
    private final Predicate<String> referenceScope;

    /**
     * An indexer that records references to Mojang's own classes only. Every class references the
     * JDK and its libraries, and nothing can look those up anyway, so keeping them would be heap
     * spent on answers no query asks for.
     */
    public Indexer() {
        this(owner -> owner.startsWith("net/minecraft") || owner.startsWith("com/mojang"));
    }

    /**
     * An indexer that records references whose target class passes {@code referenceScope} - for a
     * jar whose own code isn't all Mojang's, like a server fork's org/bukkit and io/papermc classes,
     * where the Mojang-only default would drop every reference to them.
     */
    public Indexer(Predicate<String> referenceScope) {
        this.referenceScope = referenceScope;
    }

    public void index(byte[] classBytes) {
        new ClassReader(classBytes).accept(new ClassIndexVisitor(this), ClassReader.SKIP_FRAMES);
    }

    public void indexDeclarations(byte[] classBytes) {
        new ClassReader(classBytes).accept(
                new DeclarationIndexVisitor(this),
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    public Set<String> references(String key) {
        return Set.copyOf(references.getOrDefault(key, Set.of()));
    }

    // Persistence hooks for the on-disk derived cache, which saves a full Indexer's reference
    // state across server restarts (see io.github.ccbitz.mcsrcmcp.server.DerivedCacheStore). Purely
    // additive - no existing method's behavior changes.
    public Map<String, Set<String>> allReferences() {
        Map<String, Set<String>> result = new HashMap<>();
        references.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    public void loadReferences(Map<String, Set<String>> loadedReferences) {
        references.clear();
        loadedReferences.forEach((key, value) -> references.put(key, new HashSet<>(value)));
    }

    /**
     * Merges {@code other} into this indexer. The parallel build shards classes across one Indexer
     * per worker, so this is how the shards reunite. Class and member keys are the visited class
     * itself, so those maps are disjoint across workers and a plain put is exact; reference keys
     * are the REFERENCED class/member, which any number of workers can share, so those sets union.
     * Callers may {@link #clear()} each absorbed indexer right after to free its copy while the
     * rest are still merging.
     */
    public void addAll(Indexer other) {
        classes.putAll(other.classes);
        members.putAll(other.members);
        other.references.forEach((key, value) -> references.computeIfAbsent(key, ignored -> new HashSet<>()).addAll(value));
    }

    public int referenceCount() {
        return references.values().stream().mapToInt(Set::size).sum();
    }

    public IndexData data() {
        Map<String, MemberData> memberData = new HashMap<>();
        members.forEach((name, data) -> memberData.put(name, data.snapshot()));
        return new IndexData(classes, memberData);
    }

    public void clear() {
        references.clear();
        classes.clear();
        members.clear();
    }

    // A key is a class ("a/B") or a member of one ("a/B:name:desc"); either way the class leads it.
    void addReference(String key, String value) {
        int colon = key.indexOf(':');
        String owner = colon < 0 ? key : key.substring(0, colon);
        if (referenceScope.test(owner)) {
            references.computeIfAbsent(key, ignored -> new HashSet<>()).add(value);
        }
    }

    void addClass(String name, String superName, String[] interfaces, int access) {
        classes.put(name, new ClassData(name, superName, interfaces == null ? List.of() : List.of(interfaces), access));
    }

    void addMethod(Entry.Method method) {
        members.computeIfAbsent(method.owner(), MutableMemberData::new).methods.add(method);
    }

    void addField(Entry.Field field) {
        members.computeIfAbsent(field.owner(), MutableMemberData::new).fields.add(field);
    }

    private static final class MutableMemberData {
        private final String className;
        private final Set<Entry.Method> methods = new HashSet<>();
        private final Set<Entry.Field> fields = new HashSet<>();

        private MutableMemberData(String className) {
            this.className = className;
        }

        private MemberData snapshot() {
            return new MemberData(className, methods, fields);
        }
    }

    private static final class DeclarationIndexVisitor extends ClassVisitor {
        private final Indexer indexer;
        private String className;

        private DeclarationIndexVisitor(Indexer indexer) {
            super(Opcodes.ASM9);
            this.indexer = indexer;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
            indexer.addClass(name, superName, interfaces, access);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            indexer.addField(new Entry.Field(className, name, descriptor));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            indexer.addMethod(new Entry.Method(className, name, descriptor));
            return null;
        }
    }
}
