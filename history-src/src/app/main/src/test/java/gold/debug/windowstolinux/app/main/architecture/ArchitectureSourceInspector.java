package gold.debug.windowstolinux.app.main.architecture;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/** Reads attributed Java syntax for architecture checks without generating classes. / 读取带类型信息的 Java 语法以检查架构，不生成类文件。 */
final class ArchitectureSourceInspector {
    private static List<JavaSource> production;

    private ArchitectureSourceInspector() {
    }

    static synchronized List<JavaSource> production() throws IOException {
        if (production == null) {
            try (Stream<Path> files = Files.walk(projectRoot().resolve("src"));
                    var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
                List<Path> paths = files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                        .filter(path -> path.toString().endsWith(".java")).sorted().toList();
                production = inspect(manager.getJavaFileObjectsFromPaths(paths));
            }
        }
        return production;
    }

    static List<JavaSource> snippets(Map<String, String> sources) throws IOException {
        List<JavaFileObject> files = sources.entrySet().stream()
                .map(entry -> (JavaFileObject) new SimpleJavaFileObject(
                        URI.create("string:///" + entry.getKey().replace('.', '/') + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return entry.getValue();
                    }
                }).toList();
        return inspect(files);
    }

    private static List<JavaSource> inspect(Iterable<? extends JavaFileObject> files) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "-Xlint:none", "--release", "21", "-classpath",
                            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))),
                    null, files);
            List<CompilationUnitTree> trees = new ArrayList<>();
            task.parse().forEach(trees::add);
            Map<CompilationUnitTree, List<String>> sourceReferences = new java.util.IdentityHashMap<>();
            for (CompilationUnitTree tree : trees) {
                var references = new LinkedHashSet<String>();
                tree.getImports().forEach(item -> references.add(item.getQualifiedIdentifier().toString()));
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        if (node.toString()
                                .matches("gold\\.debug\\.windowstolinux\\..*\\.[A-Z][A-Za-z0-9_]*(?:\\..*)?"))
                            references.add(node.toString());
                        return super.visitMemberSelect(node, unused);
                    }
                }.scan(tree, null);
                sourceReferences.put(tree, List.copyOf(references));
            }
            // Attribution expands var types; keep written references separate from inferred contracts. / 类型归属会展开 var 类型，需区分显式引用与推断契约。
            task.analyze();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(item -> item.getKind() == Diagnostic.Kind.ERROR).map(Object::toString).toList();
            if (!errors.isEmpty())
                throw new IllegalStateException("Architecture source attribution failed: " + errors);
            Trees syntax = Trees.instance(task);
            var types = task.getTypes();
            TypeMirror throwable = task.getElements().getTypeElement("java.lang.Throwable").asType();
            TypeMirror carrier = task.getElements()
                    .getTypeElement("gold.debug.windowstolinux.shared.model.failure.FailureCarrier").asType();
            TypeMirror definition = task.getElements()
                    .getTypeElement("gold.debug.windowstolinux.shared.model.failure.FailureDefinition").asType();
            List<JavaSource> result = new ArrayList<>();
            for (CompilationUnitTree tree : trees) {
                List<JavaType> declarations = new ArrayList<>();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        if (!node.getSimpleName().isEmpty()) {
                            TypeElement element = (TypeElement) syntax.getElement(getCurrentPath());
                            TypeMirror type = types.erasure(element.asType());
                            declarations.add(
                                    new JavaType(element.getQualifiedName().toString(), node.getSimpleName().toString(),
                                            node.getKind(), types.isAssignable(type, throwable),
                                            types.isAssignable(type, carrier), types.isAssignable(type, definition)));
                        }
                        return super.visitClass(node, unused);
                    }
                }.scan(tree, null);
                URI uri = tree.getSourceFile().toUri();
                Path path = uri.getScheme().equals("file") ? Path.of(uri) : Path.of(uri.getPath());
                result.add(new JavaSource(path, tree.getPackageName().toString(), sourceReferences.get(tree),
                        List.copyOf(declarations)));
            }
            return List.copyOf(result);
        }
    }

    static Path projectRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("docs/File.md")))
            root = root.getParent();
        if (root == null)
            throw new IllegalStateException("Project root unavailable");
        return root;
    }

    record JavaSource(Path path, String packageName, List<String> references, List<JavaType> types) {
    }

    record JavaType(String name, String simpleName, Tree.Kind kind, boolean throwable, boolean failureCarrier,
            boolean failureDefinition) {
    }
}
