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
final class ArchitectureSourceChecker {
    private static List<JavaSource> production;

    private ArchitectureSourceChecker() {
    }

    static synchronized List<JavaSource> production() throws IOException {
        if (production == null) {
            try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
                production = inspect(manager.getJavaFileObjectsFromPaths(productionPaths()));
            }
        }
        return production;
    }

    static List<Path> productionPaths() throws IOException {
        List<Path> paths = new ArrayList<>();
        for (var module : activeModules()) {
            Path source = module.pom().getParent().resolve("src/main/java");
            if (!module.aggregate() && Files.isDirectory(source))
                try (Stream<Path> files = Files.walk(source)) {
                    paths.addAll(files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java")).toList());
                }
        }
        paths.sort(Path::compareTo);
        return List.copyOf(paths);
    }

    private static List<MavenModuleGraph.Module> activeModules() throws IOException {
        try {
            return MavenModuleGraph.read(projectRoot());
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot resolve the active Maven source graph", failure);
        }
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
            // Include inferred contracts after attribution without emitting class files. / 类型解析后纳入推断合同，不生成类文件。
            task.analyze();
            List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR).map(Object::toString).toList();
            if (!errors.isEmpty())
                throw new IllegalStateException("Architecture source attribution failed: " + errors);
            Trees syntax = Trees.instance(task);
            var types = task.getTypes();
            TypeMirror throwable = task.getElements().getTypeElement("java.lang.Throwable").asType();
            TypeMirror sqlException = task.getElements().getTypeElement("java.sql.SQLException").asType();
            TypeMirror problem = task.getElements().getTypeElement("gold.debug.windowstolinux.appapi.task.result.Problem")
                .asType();
            TypeMirror optionalProblem = types.getDeclaredType(task.getElements().getTypeElement("java.util.Optional"),
                problem);
            TypeMirror outcome = task.getElements().getTypeElement("gold.debug.windowstolinux.appapi.task.result.TaskResult")
                .asType();
            TypeMirror uuid = task.getElements().getTypeElement("java.util.UUID").asType();
            List<JavaSource> result = new ArrayList<>();
            for (CompilationUnitTree tree : trees) {
                List<JavaType> declarations = new ArrayList<>();
                var calls = new LinkedHashSet<String>();
                var references = new LinkedHashSet<>(sourceReferences.get(tree));
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void scan(Tree node, Void unused) {
                        if (node != null) {
                            var path = getCurrentPath() == null
                                ? new com.sun.source.util.TreePath(tree)
                                : new com.sun.source.util.TreePath(getCurrentPath(), node);
                            collectTypes(syntax.getTypeMirror(path), references, new java.util.HashSet<>());
                            var element = syntax.getElement(path);
                            if (element != null) {
                                collectTypes(element.asType(), references, new java.util.HashSet<>());
                                if (element.getEnclosingElement() instanceof TypeElement owner
                                    && owner.getQualifiedName().toString().startsWith("gold.debug.windowstolinux."))
                                    references.add(owner.getQualifiedName().toString());
                            }
                        }
                        return super.scan(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        if (syntax.getElement(getCurrentPath()) instanceof javax.lang.model.element.ExecutableElement method
                            && method.getEnclosingElement() instanceof TypeElement owner
                            && owner.getQualifiedName().toString().startsWith("gold.debug.windowstolinux."))
                            calls.add(owner.getQualifiedName() + "#" + method.getSimpleName());
                        return super.visitMemberSelect(node, unused);
                    }

                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        if (!node.getSimpleName().isEmpty()) {
                            TypeElement element = (TypeElement) syntax.getElement(getCurrentPath());
                            TypeMirror type = types.erasure(element.asType());
                            var accessors = javax.lang.model.util.ElementFilter
                                .methodsIn(task.getElements().getAllMembers(element)).stream()
                                .filter(method -> method.getParameters().isEmpty()
                                    && method.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
                                    && !method.getModifiers()
                                    .contains(javax.lang.model.element.Modifier.STATIC))
                                .toList();
                            boolean classifiedOutcome = accessors.stream()
                                .anyMatch(method -> method.getSimpleName().contentEquals("outcome")
                                    && types.isSameType(method.getReturnType(), outcome));
                            boolean publicProblem = accessors.stream()
                                .anyMatch(method -> method.getSimpleName().contentEquals("problem")
                                    && (types.isSameType(method.getReturnType(), problem) || classifiedOutcome
                                    && types.isSameType(method.getReturnType(), optionalProblem)));
                            boolean problemFactory = javax.lang.model.util.ElementFilter.methodsIn(element.getEnclosedElements())
                                .stream().anyMatch(method -> method.getSimpleName().contentEquals("problem")
                                    && method.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
                                    && !method.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                                    && types.isSameType(method.getReturnType(), problem)
                                    && method.getParameters().stream()
                                    .anyMatch(parameter -> types.isSameType(parameter.asType(), uuid)));
                            declarations.add(
                                new JavaType(element.getQualifiedName().toString(), node.getSimpleName().toString(),
                                    node.getKind(), types.isAssignable(type, throwable),
                                    problemFactory, publicProblem, types.isAssignable(type, sqlException)));
                        }
                        return super.visitClass(node, unused);
                    }
                }.scan(tree, null);
                URI uri = tree.getSourceFile().toUri();
                Path path = uri.getScheme().equals("file") ? Path.of(uri) : Path.of(uri.getPath());
                result.add(new JavaSource(path, tree.getPackageName().toString(), List.copyOf(references),
                    List.copyOf(declarations), List.copyOf(calls)));
            }
            return List.copyOf(result);
        }
    }

    private static void collectTypes(TypeMirror type, java.util.Set<String> references,
                                     java.util.Set<String> visited) {
        if (type == null || !visited.add(type.getKind() + ":" + type))
            return;
        switch (type.getKind()) {
            case DECLARED -> {
                var declared = (javax.lang.model.type.DeclaredType) type;
                var owner = (TypeElement) declared.asElement();
                if (owner.getQualifiedName().toString().startsWith("gold.debug.windowstolinux."))
                    references.add(owner.getQualifiedName().toString());
                collectTypes(declared.getEnclosingType(), references, visited);
                declared.getTypeArguments().forEach(value -> collectTypes(value, references, visited));
            }
            case ARRAY ->
                collectTypes(((javax.lang.model.type.ArrayType) type).getComponentType(), references, visited);
            case TYPEVAR ->
                collectTypes(((javax.lang.model.type.TypeVariable) type).getUpperBound(), references, visited);
            case WILDCARD -> {
                var wildcard = (javax.lang.model.type.WildcardType) type;
                collectTypes(wildcard.getExtendsBound(), references, visited);
                collectTypes(wildcard.getSuperBound(), references, visited);
            }
            case INTERSECTION -> ((javax.lang.model.type.IntersectionType) type).getBounds()
                .forEach(value -> collectTypes(value, references, visited));
            case UNION -> ((javax.lang.model.type.UnionType) type).getAlternatives()
                .forEach(value -> collectTypes(value, references, visited));
            case EXECUTABLE -> {
                var method = (javax.lang.model.type.ExecutableType) type;
                collectTypes(method.getReturnType(), references, visited);
                method.getParameterTypes().forEach(value -> collectTypes(value, references, visited));
                method.getThrownTypes().forEach(value -> collectTypes(value, references, visited));
            }
            default -> {
            }
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

    record JavaSource(Path path, String packageName, List<String> references, List<JavaType> types,
                      List<String> calls) {
    }

    record JavaType(String name, String simpleName, Tree.Kind kind, boolean throwable,
                    boolean problemFactory, boolean publicProblem, boolean sqlException) {
    }
}
