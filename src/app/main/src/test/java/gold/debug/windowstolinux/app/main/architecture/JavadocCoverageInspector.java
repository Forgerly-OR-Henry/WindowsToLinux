package gold.debug.windowstolinux.app.main.architecture;

import com.sun.source.doctree.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Inspects written declarations before javac inserts record members or default constructors. */
final class JavadocCoverageInspector {
    private static final Pattern CHINESE = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]{2,}");
    private JavadocCoverageInspector() { }

    static List<Path> productionPaths() throws IOException {
        try (var paths = Files.walk(ArchitectureSourceInspector.projectRoot().resolve("src"))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/').matches(".*/src/(shared|app|web)/[^/]+/src/main/java/.*\\.java"))
                    .sorted().toList();
        }
    }

    static Inspection production() throws IOException {
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            return inspect(manager.getJavaFileObjectsFromPaths(productionPaths()));
        }
    }

    static Inspection snippets(String source) throws IOException {
        var file = new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        return inspect(List.of(file));
    }

    static long implementationLines(String source) throws IOException {
        var file = new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source; }
        };
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                    List.of("-proc:none", "--release", "21"), null, List.of(file));
            var docs = DocTrees.instance(task);
            var documented = new BitSet(source.length());
            for (var unit : task.parse()) new TreePathScanner<Void, Void>() {
                private void mark() {
                    var doc = docs.getDocCommentTree(getCurrentPath());
                    if (doc == null) return;
                    long body = docs.getSourcePositions().getStartPosition(unit, doc, doc);
                    if (body < 0) body = docs.getSourcePositions().getStartPosition(unit, getCurrentPath().getLeaf());
                    int start = source.lastIndexOf("/**", (int) body), end = source.indexOf("*/", start) + 2;
                    if (start >= 0 && end > start) documented.set(start, end);
                }
                @Override public Void visitClass(ClassTree node, Void unused) { mark(); return super.visitClass(node, unused); }
                @Override public Void visitMethod(MethodTree node, Void unused) { mark(); return super.visitMethod(node, unused); }
                @Override public Void visitVariable(VariableTree node, Void unused) { mark(); return super.visitVariable(node, unused); }
            }.scan(unit, null);
            long count = 0; int offset = 0;
            for (String line : source.split("\n", -1)) {
                if (offset == source.length()) break;
                boolean comment = false, content = false;
                for (int i = 0; i < line.length(); i++) {
                    if (documented.get(offset + i)) comment = true;
                    else if (!Character.isWhitespace(line.charAt(i))) content = true;
                }
                if (!comment || content) count++;
                offset += line.length() + 1;
            }
            return count;
        }
    }

    private static Inspection inspect(Iterable<? extends JavaFileObject> files) throws IOException {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null, files);
            var docs = DocTrees.instance(task);
            var positions = docs.getSourcePositions();
            List<String> violations = new ArrayList<>();
            int[] count = {0};
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    private boolean component(VariableTree variable, ClassTree owner) {
                        // Records cannot declare additional instance fields. / record 不允许声明额外实例字段。
                        return owner.getKind() == Tree.Kind.RECORD
                                && !variable.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC);
                    }

                    private void check(Tree node, String name, List<String> parameters, List<String> typeParameters,
                                       boolean returns, List<String> thrown) {
                        if (positions.getStartPosition(unit, node) < 0 || positions.getEndPosition(unit, node) < 0) return;
                        count[0]++;
                        String location = unit.getSourceFile().getName() + ":" + unit.getLineMap().getLineNumber(positions.getStartPosition(unit, node)) + " " + name;
                        var doc = docs.getDocCommentTree(getCurrentPath());
                        if (doc == null) { violations.add(location + " missing Javadoc"); return; }
                        String body = prose(doc.getFullBody());
                        if (!bilingual(body) || !englishFirst(body)) violations.add(location + " requires an English summary followed by Chinese: " + body);
                        if (body.contains("{@inheritDoc}")) violations.add(location + " inherited documentation requires an explicitly verified source contract");
                        if (Pattern.compile("(?s)(Provides the \\{@code|Performs the \\{@code|the \\{@code [^}]+} value|提供 \\{@code [^}]+} 实现|执行 \\{@code [^}]+} 操作)").matcher(body).find())
                            violations.add(location + " contains a non-semantic template summary");
                        Set<String> params = new HashSet<>(), types = new HashSet<>(), exceptions = new HashSet<>();
                        boolean hasReturn = false;
                        for (var tag : doc.getBlockTags()) {
                            List<? extends DocTree> description = null;
                            if (tag instanceof ParamTree param) {
                                (param.isTypeParameter() ? types : params).add(param.getName().toString()); description = param.getDescription();
                            } else if (tag instanceof com.sun.source.doctree.ReturnTree value) { hasReturn = true; description = value.getDescription(); }
                            else if (tag instanceof ThrowsTree exception) { exceptions.add(simple(exception.getExceptionName().toString())); description = exception.getDescription(); }
                            if (description != null && !bilingual(prose(description))) violations.add(location + " has a blank or monolingual " + tag.getKind() + " description");
                        }
                        if (!params.equals(new HashSet<>(parameters))) violations.add(location + " @param mismatch: expected " + parameters + " but found " + params);
                        if (!types.equals(new HashSet<>(typeParameters))) violations.add(location + " type @param mismatch");
                        if (returns != hasReturn) violations.add(location + " @return mismatch");
                        for (String exception : thrown) if (!exceptions.contains(simple(exception))) violations.add(location + " missing @throws " + exception);
                    }

                    @Override public Void visitClass(ClassTree node, Void unused) {
                        if (!node.getSimpleName().isEmpty()) {
                            List<String> components = node.getMembers().stream().filter(VariableTree.class::isInstance).map(VariableTree.class::cast)
                                    .filter(variable -> component(variable, node)).map(variable -> variable.getName().toString()).toList();
                            check(node, node.getSimpleName().toString(), components,
                                    node.getTypeParameters().stream().map(type -> type.getName().toString()).toList(), false, List.of());
                        }
                        return super.visitClass(node, unused);
                    }

                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        check(node, node.getName().toString(), node.getParameters().stream().map(param -> param.getName().toString()).toList(),
                                node.getTypeParameters().stream().map(type -> type.getName().toString()).toList(),
                                node.getReturnType() != null && !node.getReturnType().toString().equals("void"),
                                node.getThrows().stream().map(Object::toString).toList());
                        return super.visitMethod(node, unused);
                    }

                    @Override public Void visitVariable(VariableTree node, Void unused) {
                        if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner && !component(node, owner))
                            check(node, node.getName().toString(), List.of(), List.of(), false, List.of());
                        return super.visitVariable(node, unused);
                    }
                }.scan(unit, null);
            }
            diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).forEach(d -> violations.add(d.toString()));
            return new Inspection(count[0], List.copyOf(violations));
        }
    }

    private static String prose(List<? extends DocTree> trees) {
        return String.join("", trees.stream().map(tree -> tree instanceof TextTree text ? text.getBody() : tree.toString()).toList());
    }
    private static boolean bilingual(String text) {
        String prose = text.replaceAll("\\{@(?:code|literal|link|linkplain)\\s+[^}]*}", "").replaceAll("<[^>]+>", "");
        return CHINESE.matcher(prose).find() && ENGLISH.matcher(prose).find();
    }
    private static boolean englishFirst(String text) {
        String prose = text.replaceAll("<[^>]+>", "").strip();
        var chinese = CHINESE.matcher(prose); var english = ENGLISH.matcher(prose);
        return english.find() && chinese.find() && english.start() < chinese.start();
    }
    private static String simple(String type) { return type.substring(type.lastIndexOf('.') + 1); }
    record Inspection(int declarations, List<String> violations) { }
}
