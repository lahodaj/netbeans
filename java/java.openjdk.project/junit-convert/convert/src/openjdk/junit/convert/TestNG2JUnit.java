/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package openjdk.junit.convert;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.GeneratorUtilities;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.TreePathHandle;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.Fix;
import org.netbeans.spi.java.hints.ErrorDescriptionFactory;
import org.netbeans.spi.java.hints.Hint;
import org.netbeans.spi.java.hints.HintContext;
import org.netbeans.spi.java.hints.JavaFix;
import org.netbeans.spi.java.hints.JavaFixUtilities;
import org.netbeans.spi.java.hints.TriggerPattern;
import org.netbeans.spi.java.hints.TriggerTreeKind;
import org.openide.util.NbBundle.Messages;

@Hint(displayName = "#DN_TestNG2JUnit", description = "#DESC_TestNG2JUnit", category = "general")
@Messages({
    "DN_TestNG2JUnit=TestNG2JUnit",
    "DESC_TestNG2JUnit=TestNG to JUnit",
    "ERR_TestNG2JUnit=TestNG Element"
})
public class TestNG2JUnit {

    @TriggerTreeKind(Tree.Kind.METHOD_INVOCATION)
    public static ErrorDescription computeWarning(HintContext ctx) {
        TypeElement testNGAssert = ctx.getInfo().getElements().getTypeElement("org.testng.Assert");
        if (testNGAssert == null) {
            return null;
        }

        TypeElement testNGAssertJUnit = ctx.getInfo().getElements().getTypeElement("org.testng.AssertJUnit");
        Element el = ctx.getInfo().getTrees().getElement(ctx.getPath());

        if (el == null || (el.getEnclosingElement() != testNGAssert && el.getEnclosingElement() != testNGAssertJUnit)) {
            return null;
        }
        MethodInvocationTree mit = (MethodInvocationTree) ctx.getPath().getLeaf();
        int paramCount = 0;
        for (Tree param : mit.getArguments()) {
            ctx.getVariables().put("$param" + ++paramCount, new TreePath(ctx.getPath(), param));
        }
        String newCall = null;
        String simpleName = el.getSimpleName().toString();
        if (el.getEnclosingElement() == testNGAssertJUnit) {
            //don't flip arguments, don't rename(?)
            StringBuilder newCallBuilder = new StringBuilder();
            newCallBuilder.append("org.junit.jupiter.api.Assertions." + simpleName + "(");
            int useParamCount = 0;
            String sep = "";

            for (Tree param : mit.getArguments()) {
                newCallBuilder.append(sep);
                newCallBuilder.append("$param" + ++useParamCount);
                sep = ", ";
            }

            newCallBuilder.append(")");
            newCall = newCallBuilder.toString();
        } else {
            OUTER: switch (simpleName) {
                case "fail":
                case "assertFalse":
                case "assertTrue":
                case "assertNotNull":
                case "assertNull":
                case "assertThrows":
                case "expectThrows":
                {
                    switch (simpleName) {
                        case "expectThrows": simpleName = "assertThrows"; break; //in TestNG, assertThrows returns void, but expectThrows returns the exception. In JUnit, assertThrows returns the exception.
                        case "assertThrows":
                            if (paramCount == 1) {
                                //special-case:
                                newCall = "org.junit.jupiter.api.Assertions." + simpleName + "(Throwable.class, $param1)";
                                break OUTER;
                            }
                            break;
                    }
                    switch (paramCount) {
                        case 0: newCall = "org.junit.jupiter.api.Assertions." + simpleName + "()"; break;
                        case 1: newCall = "org.junit.jupiter.api.Assertions." + simpleName + "($param1)"; break;
                        case 2: newCall = "org.junit.jupiter.api.Assertions." + simpleName + "($param1, $param2)"; break;
                    }
                    break;
                }
                case "assertEquals":
                case "assertNotEquals": {
                    String orderedParams = "$param2, $param1";
                    TreePath param1 = new TreePath(ctx.getPath(), mit.getArguments().get(0));
                    TreePath param2 = new TreePath(ctx.getPath(), mit.getArguments().get(1));

                    if (looksLikeAPossibleExpectedValue(param1) && !looksLikeAPossibleExpectedValue(param2)) {
                        //do not flip the arguments if the left one looks-like an expected value:
                        orderedParams = "$param1, $param2";
                    }

                    if (paramCount >= 2 &&
                        isArray(ctx.getInfo().getTrees().getTypeMirror(param1)) &&
                        isArray(ctx.getInfo().getTrees().getTypeMirror(param2))) {

                        switch (simpleName) {
                            case "assertEquals": simpleName = "assertArrayEquals"; break;
                            case "assertNotEquals": simpleName = "assertArrayNotEquals"; break;
                            default: throw new IllegalStateException();
                        }
                    }
                    switch (paramCount) {
                        case 2: newCall = "org.junit.jupiter.api.Assertions." + simpleName + "(" + orderedParams + ")"; break;
                        case 3: newCall = "org.junit.jupiter.api.Assertions." + simpleName + "(" + orderedParams + ", $param3)"; break;
                    }
                    break;
                }


            }
        }
        if (newCall != null) {
            Fix fix = JavaFixUtilities.rewriteFix(ctx, "Use JUnit Method", ctx.getPath(), newCall);
            return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), fix);
        }
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit());
    }

    private static boolean looksLikeAPossibleExpectedValue(TreePath tp) {
        return switch (tp.getLeaf().getKind()) {
            case IDENTIFIER -> ((IdentifierTree) tp.getLeaf()).getName().toString().toLowerCase(Locale.US).contains("expected");
            case BOOLEAN_LITERAL, CHAR_LITERAL, DOUBLE_LITERAL, FLOAT_LITERAL,
                 INT_LITERAL, LONG_LITERAL, NULL_LITERAL, STRING_LITERAL -> true;
            case TYPE_CAST -> looksLikeAPossibleExpectedValue(new TreePath(tp, ((TypeCastTree) tp.getLeaf()).getExpression()));
            default -> false;
        };
    }

    @TriggerPattern("org.testng.annotations.AfterMethod")
    public static ErrorDescription afterMethod(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.AfterEach"));
    }

    @TriggerPattern("org.testng.annotations.BeforeMethod")
    public static ErrorDescription beforeMethod(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.BeforeEach"));
    }

    @TriggerPattern("org.testng.annotations.AfterClass")
    public static ErrorDescription afterClass(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.AfterAll"));
    }

    @TriggerPattern("org.testng.annotations.BeforeClass")
    public static ErrorDescription beforeClass(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.BeforeAll"));
    }

    @TriggerPattern("org.testng.annotations.AfterTest") //not fully correct, but might be OK for JDK
    public static ErrorDescription afterTest(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.AfterAll"));
    }

    @TriggerPattern("org.testng.annotations.BeforeTest") //not fully correct, but might be OK for JDK
    public static ErrorDescription beforeTest(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.BeforeAll"));
    }

    @TriggerPattern("org.testng.annotations.AfterSuite") //unclear - is this sensible?
    public static ErrorDescription aftersuite(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.AfterAll"));
    }

    @TriggerPattern("org.testng.annotations.BeforeSuite") //unclear - is this sensible?
    public static ErrorDescription beforeSuite(HintContext ctx) {
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.BeforeAll"));
    }

    @TriggerPattern("org.testng.annotations.DataProvider")
    public static ErrorDescription dataProvider(HintContext ctx) {
        if (ctx.getPath().getParentPath().getLeaf().getKind() == Tree.Kind.ANNOTATION) {
            return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), new RemoveAnnotation(ctx.getInfo(), ctx.getPath().getParentPath()).toEditorFix());
        }
        return null;
    }

    @TriggerPattern("org.testng.annotations.NoInjection")
    public static ErrorDescription noInjection(HintContext ctx) {
        if (ctx.getPath().getParentPath().getLeaf().getKind() == Tree.Kind.ANNOTATION &&
            ctx.getPath().getParentPath().getParentPath().getParentPath().getLeaf().getKind() == Tree.Kind.VARIABLE) {
            TreePath varPath = ctx.getPath().getParentPath().getParentPath().getParentPath();
            if ("java.lang.reflect.Method".equals(String.valueOf(ctx.getInfo().getTrees().getTypeMirror(varPath)))) {
                //JUnit does not (specially) inject Method parameters, no need for NoInjection(?)
                return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), new RemoveAnnotation(ctx.getInfo(), ctx.getPath().getParentPath()).toEditorFix());
            }
        }

        return null;
    }

    @TriggerPattern("throw new org.testng.SkipException($message);")
    public static ErrorDescription skipException(HintContext ctx) {
        //XXX: this may interact badly with assertThrows
        TreePath baseTP = ctx.getPath();
        if (baseTP.getParentPath().getLeaf().getKind() == Tree.Kind.BLOCK) {
            BlockTree bt = (BlockTree) baseTP.getParentPath().getLeaf();
            if (bt.getStatements().size() != 1) {
                return naiveSkipExceptionRewrite(ctx);
            }
            baseTP = baseTP.getParentPath();
        }
        if (baseTP.getParentPath().getLeaf().getKind() == Tree.Kind.IF) {
            TreePath ifPath = baseTP.getParentPath();
            IfTree it = (IfTree) ifPath.getLeaf();
            String assumeMethod;
            StatementTree notThrowingBranch;
            if (it.getThenStatement() == baseTP.getLeaf()) {
                assumeMethod = "assumeFalse";
                notThrowingBranch = it.getElseStatement();
            } else if (it.getElseStatement() == baseTP.getLeaf()) {
                assumeMethod = "assumeTrue";
                notThrowingBranch = it.getThenStatement();
            } else {
                return naiveSkipExceptionRewrite(ctx);
            }
            if (notThrowingBranch == null) {//XXX
                ctx.getVariables().put("$condition", new TreePath(ifPath, it.getCondition()));
                Fix fix = JavaFixUtilities.rewriteFix(ctx, "Use Assumptions", ifPath, "org.junit.jupiter.api.Assumptions." + assumeMethod + "($condition, $message);", "0");
                return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), fix);
            } else if (ifPath.getParentPath().getLeaf().getKind() == Tree.Kind.BLOCK) {
                TreePath blockPath = ifPath.getParentPath();
                BlockTree enclosing = (BlockTree) blockPath.getLeaf();
                int ifIdx = enclosing.getStatements().indexOf(it);
                
                ctx.getVariables().put("$condition", new TreePath(ifPath, it.getCondition()));
                ctx.getMultiVariables().put("$blockPrefix$", enclosing.getStatements().subList(0, ifIdx).stream().map(s -> new TreePath(blockPath, s)).toList());
                ctx.getMultiVariables().put("$blockSuffix$", enclosing.getStatements().subList(ifIdx + 1, enclosing.getStatements().size()).stream().map(s -> new TreePath(blockPath, s)).toList());
                if (notThrowingBranch.getKind() == Tree.Kind.BLOCK) {
                    ctx.getMultiVariables().put("$content$", ((BlockTree) notThrowingBranch).getStatements().stream().map(s -> new TreePath(new TreePath(ifPath, notThrowingBranch), s)).toList());
                } else {
                    ctx.getMultiVariables().put("$content$", List.of(new TreePath(ifPath, notThrowingBranch)));
                }

                Fix fix = JavaFixUtilities.rewriteFix(ctx, "Use Assumptions", ifPath, "$blockPrefix$; org.junit.jupiter.api.Assumptions." + assumeMethod + "($condition, $message); $content$; $blockSuffix$;", "0");
                return ErrorDescriptionFactory.forName(ctx, blockPath, Bundle.ERR_TestNG2JUnit(), fix);
            }
        }

        return naiveSkipExceptionRewrite(ctx);
    }

    public static ErrorDescription naiveSkipExceptionRewrite(HintContext ctx) {
        //TODO: do we want these naive re-writes??
        Fix fix = JavaFixUtilities.rewriteFix(ctx, "Use Assumptions", ctx.getPath(), "org.junit.jupiter.api.Assumptions.assumeTrue(false, $message);", "0");
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), fix);
    }

    @TriggerTreeKind(Tree.Kind.IMPORT)
    public static ErrorDescription removeImports(HintContext ctx) {
        ImportTree it = (ImportTree) ctx.getPath().getLeaf();
        TreePath selectPath = new TreePath(ctx.getPath(), it.getQualifiedIdentifier());
        Element el = ctx.getInfo().getTrees().getElement(selectPath);
        if (el == null || !(el.getKind().isClass() || el.getKind().isInterface())) {
            MemberSelectTree qualified = (MemberSelectTree) it.getQualifiedIdentifier();

            el = ctx.getInfo().getTrees().getElement(new TreePath(selectPath, qualified.getExpression()));
        }
        if (el == null || !isTestNGElement(ctx.getInfo(), el)) {
            return null;
        }
        Fix fix;
        if (el.getKind() == ElementKind.CLASS && ((QualifiedNameable) el).getQualifiedName().contentEquals("org.testng.Assert") && it.isStatic()) {
            fix = new ChangeStaticImport(ctx.getInfo(), ctx.getPath(), "org.junit.jupiter.api.Assertions", ((MemberSelectTree) it.getQualifiedIdentifier()).getIdentifier().toString()).toEditorFix();
        } else {
            fix = new RemoveImport(ctx.getInfo(), ctx.getPath()).toEditorFix();
        }
        return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), fix);
    }

    @TriggerPattern("org.testng.annotations.Test")
    public static ErrorDescription testAnnotation(HintContext ctx) {
        TreePath annotatedElement;
        boolean disable;
        String dataProviderName;
        String expectedException;
        boolean canConvert = true;
        if (ctx.getPath().getParentPath().getLeaf().getKind() == Tree.Kind.ANNOTATION) {
            annotatedElement = ctx.getPath().getParentPath().getParentPath().getParentPath();

            AnnotationTree at = (AnnotationTree) ctx.getPath().getParentPath().getLeaf();
            Map<String, ExpressionTree> attribute2Value = new HashMap<>();

            for (ExpressionTree arg : at.getArguments()) {
                switch (arg.getKind()) {
                    case ASSIGNMENT:
                        AssignmentTree assign = (AssignmentTree) arg;
                        attribute2Value.put(assign.getVariable().toString(), assign.getExpression());
                        break;
                    default:
                        attribute2Value.put("value", arg);
                        break;
                }
            }

            ExpressionTree enabledTree = attribute2Value.get("enabled");

            disable = enabledTree != null && enabledTree.getKind() == Tree.Kind.BOOLEAN_LITERAL && !(Boolean) ((LiteralTree) enabledTree).getValue();

            ExpressionTree dataProviderTree = attribute2Value.get("dataProvider");

            String dataProviderKey = dataProviderTree != null && dataProviderTree.getKind() == Tree.Kind.STRING_LITERAL ? (String) ((LiteralTree) dataProviderTree).getValue() : null;

            if (dataProviderKey != null) {
                dataProviderName = null;

                Element annotatedElementEl = ctx.getInfo().getTrees().getElement(annotatedElement);
                FOUND: for (ExecutableElement candidate : ElementFilter.methodsIn(ctx.getInfo().getElements().getAllMembers((TypeElement) annotatedElementEl.getEnclosingElement()))) {
                    for (AnnotationMirror am : candidate.getAnnotationMirrors()) {
                        if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals("org.testng.annotations.DataProvider")) {
                            String name = candidate.getSimpleName().toString();

                            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
                                if (e.getKey().getSimpleName().contentEquals("name")) {
                                    name = String.valueOf(e.getValue().getValue());
                                }
                            }

                            if (dataProviderKey.equals(name)) {
                                //TODO: should be better to rename the method??
                                dataProviderName = candidate.getSimpleName().toString();
                            }
                        }
                    }
                }
                if (dataProviderName == null) {
                    dataProviderName = "TODO: cannot automatically find data provider named '" + dataProviderKey + "', please resolve manually.";
                }
            } else {
                dataProviderName = null;
            }

            ExpressionTree expectedExceptionsTree = attribute2Value.get("expectedExceptions");

            if (expectedExceptionsTree != null) {
                if (expectedExceptionsTree.getKind() == Tree.Kind.NEW_ARRAY) {
                    NewArrayTree value = (NewArrayTree) expectedExceptionsTree;
                    if (value.getInitializers().size() == 1) {
                        expectedExceptionsTree = value.getInitializers().get(0);
                    }
                }
                if (expectedExceptionsTree.getKind() == Tree.Kind.MEMBER_SELECT) {
                    ExpressionTree exceptionClass = ((MemberSelectTree) expectedExceptionsTree).getExpression();

                    expectedException = exceptionClass.toString();
                } else {
                    canConvert = false;
                    expectedException = null;
                }
            } else {
                expectedException = null;
            }
            //TODO: fail if unknown/unresolvable attributes are present
        } else {
            annotatedElement = ctx.getPath();
            disable = false;
            dataProviderName = null;
            expectedException = null;
        }
        //TODO: parameters to the annotation!
        Fix fix;
        switch (annotatedElement.getLeaf().getKind()) {
            case METHOD: {
                List<TreePathHandle> placesToAugment = new ArrayList<>();
                if (dataProviderName == null) {
                    placesToAugment.add(TreePathHandle.create(annotatedElement, ctx.getInfo()));
                }
                fix = new AddTestAnnotations(ctx.getInfo(), ctx.getPath().getParentPath(), placesToAugment, disable, dataProviderName, expectedException).toEditorFix();
                break;
            }
            case CLASS: {
                List<TreePathHandle> placesToAugment = new ArrayList<>();
                MEMBERS: for (Tree member : ((ClassTree) annotatedElement.getLeaf()).getMembers()) {
                    if (member.getKind() != Tree.Kind.METHOD) {
                        continue;
                    }
                    MethodTree method = (MethodTree) member;
                    if (!method.getModifiers().getFlags().contains(Modifier.PUBLIC) || method.getReturnType() == null) {
                        continue;
                    }
                    TreePath methodTP = new TreePath(annotatedElement, method);
                    TreePath modifiersTP = new TreePath(methodTP, method.getModifiers());
                    for (AnnotationTree at : method.getModifiers().getAnnotations()) {
                        if (isTestNGElement(ctx.getInfo(), ctx.getInfo().getTrees().getElement(new TreePath(new TreePath(modifiersTP, at), at.getAnnotationType())))) {
                            continue MEMBERS;
                        }
                    }
                    placesToAugment.add(TreePathHandle.create(methodTP, ctx.getInfo()));
                }
                fix = new AddTestAnnotations(ctx.getInfo(), ctx.getPath().getParentPath(), placesToAugment, disable, dataProviderName, expectedException).toEditorFix();
                break;
            }
            default:
                fix = JavaFixUtilities.rewriteFix(ctx, "Use JUnit annotation", ctx.getPath(), "org.junit.jupiter.api.Test");
                break;
        }
        if (canConvert) {
            return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), fix);
        } else {
            return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit());
        }
    }

    private static boolean isTestNGElement(CompilationInfo info, Element el) {
        while (el.getKind() != ElementKind.PACKAGE) {
            el = el.getEnclosingElement();
        }
        String packageName = ((PackageElement) el).getQualifiedName().toString();
        return packageName.startsWith("org.testng");
    }

    @TriggerTreeKind(Tree.Kind.COMPILATION_UNIT)
    public static List<ErrorDescription> changeTestRunner(HintContext ctx) {
        if (ctx.getInfo().getText().contains("@run testng")) {
            List<ErrorDescription> result = new ArrayList<>();
            result.add(ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_TestNG2JUnit(), new RunJUnit(ctx.getInfo(), ctx.getPath()).toEditorFix()));
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void p) {
                    Element el = ctx.getInfo().getTrees().getElement(getCurrentPath());
                    if (el != null && el.getKind().isClass() && requiresPerClassLifecycle(ctx.getInfo(), (TypeElement) el)) {
                        result.add(ErrorDescriptionFactory.forName(ctx, getCurrentPath(), "Requires per-class lifecycle", new AddPerClassLifecycleAnnotation(ctx.getInfo(), getCurrentPath()).toEditorFix()));
                    }
                    return super.visitClass(node, p);
                }
            }.scan(ctx.getInfo().getCompilationUnit(), null);
            return result;
        }
        return null;
    }

    private static final Set<String> REQUIRES_PER_CLASS_LIFECYCLE_ANNOTATIONS = Set.of(
        "org.testng.annotations.AfterClass",
        "org.testng.annotations.BeforeClass",
        "org.testng.annotations.AfterTest",
        "org.testng.annotations.BeforeTest",
        "org.testng.annotations.DataProvider");

    private static boolean requiresPerClassLifecycle(CompilationInfo info, TypeElement te) {
        for (ExecutableElement candidate : ElementFilter.methodsIn(info.getElements().getAllMembers(te))) {
            for (AnnotationMirror am : candidate.getAnnotationMirrors()) {
                if (REQUIRES_PER_CLASS_LIFECYCLE_ANNOTATIONS.contains(((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().toString())) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isArray(TypeMirror type) {
        return type != null && type.getKind() == TypeKind.ARRAY;
    }

    private static final class AddTestAnnotations extends JavaFix {

        private final List<TreePathHandle> placesToAugment;
        private final boolean disable;
        private final String dataProviderName;
        private final String expectedException;

        public AddTestAnnotations(CompilationInfo info, TreePath tp, List<TreePathHandle> placesToAugment, boolean disable, String dataProviderName, String expectedException) {
            super(info, tp);
            this.placesToAugment = placesToAugment;
            this.disable = disable;
            this.dataProviderName = dataProviderName;
            this.expectedException = expectedException;
        }

        @Override
        protected String getText() {
            return "Add test annotations";
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            TreeMaker make = tc.getWorkingCopy().getTreeMaker();
            GeneratorUtilities gu = GeneratorUtilities.get(tc.getWorkingCopy());
            AnnotationTree originalAnnotation = (AnnotationTree) tc.getPath().getLeaf();

            for (TreePathHandle toAugment : placesToAugment) {
                TreePath member = toAugment.resolve(tc.getWorkingCopy());

                if (member == null) {
                    //TODO: log?
                    continue;
                }

                MethodTree method = (MethodTree) member.getLeaf();
                AnnotationTree newTestAnnotation = make.Annotation(make.QualIdent("org.junit.jupiter.api.Test"), Collections.emptyList());

                gu.copyComments(originalAnnotation, newTestAnnotation, false);
                gu.copyComments(originalAnnotation, newTestAnnotation, true);

                ModifiersTree mods = method.getModifiers();

                mods = make.addModifiersAnnotation(mods, newTestAnnotation);
                mods = make.removeModifiersModifier(mods, Modifier.STATIC);
                mods = make.removeModifiersModifier(mods, Modifier.PRIVATE);

                tc.getWorkingCopy().rewrite(method.getModifiers(), mods);

                if (expectedException != null) {
                    resolveAssertThrows(tc, member);
                }
            }

            //remove original annotation:
            TreePath modifiersTP = tc.getPath().getParentPath();
            ModifiersTree mt = (ModifiersTree) modifiersTP.getLeaf();
            mt = (ModifiersTree) tc.getWorkingCopy().resolveRewriteTarget(mt);
            tc.getWorkingCopy().rewrite(mt, mt = make.removeModifiersAnnotation(mt, originalAnnotation));

            if (disable) {
                AnnotationTree disableAnnotation = make.Annotation(make.QualIdent("org.junit.jupiter.api.Disabled"), Collections.emptyList());

                tc.getWorkingCopy().rewrite(mt, mt = make.addModifiersAnnotation(mt, disableAnnotation));
            }
            if (dataProviderName != null) {
                AnnotationTree newTestAnnotation = make.Annotation(make.QualIdent("org.junit.jupiter.params.ParameterizedTest"), Collections.emptyList());

                gu.copyComments(originalAnnotation, newTestAnnotation, false);
                gu.copyComments(originalAnnotation, newTestAnnotation, true);
                tc.getWorkingCopy().rewrite(mt, mt = make.addModifiersAnnotation(mt, newTestAnnotation));
                tc.getWorkingCopy().rewrite(mt, mt = make.removeModifiersModifier(mt, Modifier.STATIC));
                tc.getWorkingCopy().rewrite(mt, mt = make.removeModifiersModifier(mt, Modifier.PRIVATE));
                tc.getWorkingCopy().rewrite(mt, mt = make.addModifiersAnnotation(mt, make.Annotation(make.QualIdent("org.junit.jupiter.params.provider.MethodSource"), Arrays.asList(make.Literal(dataProviderName)))));

                if (expectedException != null) {
                    TreePath member = modifiersTP.getParentPath();

                    resolveAssertThrows(tc, member);
                }
            }
        }

        private void resolveAssertThrows(TransformationContext tc, TreePath member) {
            TreeMaker make = tc.getWorkingCopy().getTreeMaker();
            MethodTree method = (MethodTree) member.getLeaf();
            BlockTree body = method.getBody();

            body = (BlockTree) tc.getWorkingCopy().resolveRewriteTarget(body);

            //keep initial Assumptions outside of the assertThrows
            //assertThrows is incompatible with Assumptions, see: https://github.com/junit-team/junit-framework/discussions/4851
            //not 100% correct, of course - there may be Assumptions later in the method:
            List<StatementTree> newStatements = new ArrayList<>();
            int idx = 0;

            while (idx < body.getStatements().size()) {
                StatementTree currentStatement = body.getStatements().get(idx);
                if (currentStatement.getKind() == Tree.Kind.EXPRESSION_STATEMENT) {
                    ExpressionStatementTree es = (ExpressionStatementTree) currentStatement;
                    if (es.getExpression().getKind() == Tree.Kind.METHOD_INVOCATION) {
                        MethodInvocationTree mit = (MethodInvocationTree) es.getExpression();
                        if (mit.getMethodSelect().toString().startsWith("org.junit.jupiter.api.Assumptions.")) {
                            newStatements.add(currentStatement);
                            idx++;
                            continue;
                        }
                    }
                }
                break;
            }

            //TODO: there's also a message in the @Test annotation that can be used for assertThrows(?)
            //TODO: use QualIdent instead of MemberSelect:
            newStatements.add(make.ExpressionStatement(make.MethodInvocation(Collections.emptyList(),
                                                                             make.MemberSelect(make.QualIdent("org.junit.jupiter.api.Assertions"), "assertThrows"),
                                                                             Arrays.asList(make.MemberSelect(make.QualIdent(expectedException), "class"),
                                                                                           make.LambdaExpression(Collections.emptyList(), make.Block(body.getStatements().subList(idx, body.getStatements().size()), false))))));
            tc.getWorkingCopy().rewrite(body, make.Block(newStatements, false));
        }
    }

    private static final class RemoveImport extends JavaFix {

        public RemoveImport(CompilationInfo info, TreePath tp) {
            super(info, tp);
        }

        @Override
        protected String getText() {
            return "Remove TestNG import";
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            TreePath topLevelTP = tc.getPath().getParentPath();
            ImportTree it = (ImportTree) tc.getPath().getLeaf();
            CompilationUnitTree adjustedTopLevel = (CompilationUnitTree) tc.getWorkingCopy().resolveRewriteTarget(topLevelTP.getLeaf());
            //force comments mapped:
            tc.getWorkingCopy().getTreeUtilities().getComments(tc.getWorkingCopy().getCompilationUnit(), true);
            tc.getWorkingCopy().rewrite(adjustedTopLevel, tc.getWorkingCopy().getTreeMaker().removeCompUnitImport(adjustedTopLevel, it));
        }

    }

    private static final class ChangeStaticImport extends JavaFix {

        private final String targetClass;
        private final String member;
        public ChangeStaticImport(CompilationInfo info, TreePath tp, String targetClass, String member) {
            super(info, tp);
            this.targetClass = targetClass;
            this.member = member;
        }

        @Override
        protected String getText() {
            return "Use JUnit Assertions";
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            CompilationUnitTree adjustedTopLevel = (CompilationUnitTree) tc.getWorkingCopy().resolveRewriteTarget(tc.getPath().getCompilationUnit());
            TreeMaker make = tc.getWorkingCopy().getTreeMaker();
            GeneratorUtilities gu = GeneratorUtilities.get(tc.getWorkingCopy());
            List<ImportTree> imports = new ArrayList<>(adjustedTopLevel.getImports());
            for (int i = 0; i < imports.size(); i++) {
                if (imports.get(i) == tc.getPath().getLeaf()) {
                    imports.set(i, make.Import(make.MemberSelect(make.QualIdent(tc.getWorkingCopy().getElements().getTypeElement(targetClass)), member), true));
                }
            }
            CompilationUnitTree newTopLevel = make.CompilationUnit(adjustedTopLevel.getPackage(), imports, adjustedTopLevel.getTypeDecls(), adjustedTopLevel.getSourceFile());
            gu.copyComments(tc.getWorkingCopy().getCompilationUnit(), newTopLevel, false);
            gu.copyComments(tc.getWorkingCopy().getCompilationUnit(), newTopLevel, true);
            tc.getWorkingCopy().rewrite(tc.getWorkingCopy().getCompilationUnit(), newTopLevel);
        }

    }

    private static final class RemoveAnnotation extends JavaFix {

        public RemoveAnnotation(CompilationInfo info, TreePath tp) {
            super(info, tp);
        }

        @Override
        protected String getText() {
            return "Remove TestNG annotation";
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            ModifiersTree mt = (ModifiersTree) tc.getPath().getParentPath().getLeaf();
            mt = (ModifiersTree) tc.getWorkingCopy().resolveRewriteTarget(mt);
            tc.getWorkingCopy().rewrite(mt, tc.getWorkingCopy().getTreeMaker().removeModifiersAnnotation(mt, (AnnotationTree) tc.getPath().getLeaf()));
        }

    }

    private static final class AddPerClassLifecycleAnnotation extends JavaFix {

        public AddPerClassLifecycleAnnotation(CompilationInfo info, TreePath tp) {
            super(info, tp);
        }

        @Override
        protected String getText() {
            return "Add @TestInstance(Lifecycle.PER_CLASS)";
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            TreeMaker make = tc.getWorkingCopy().getTreeMaker();
            ModifiersTree mt = ((ClassTree) tc.getPath().getLeaf()).getModifiers();
            mt = (ModifiersTree) tc.getWorkingCopy().resolveRewriteTarget(mt);

            AnnotationTree lifecycleAnno =
                    make.Annotation(make.QualIdent("org.junit.jupiter.api.TestInstance"), Arrays.asList(make.MemberSelect(make.QualIdent("org.junit.jupiter.api.TestInstance.Lifecycle"), "PER_CLASS")));
            tc.getWorkingCopy().rewrite(mt, tc.getWorkingCopy().getTreeMaker().addModifiersAnnotation(mt, lifecycleAnno));
        }

    }

    private static final class RunJUnit extends JavaFix {

        public RunJUnit(CompilationInfo info, TreePath tp) {
            super(info, tp);
        }

        @Override
        protected String getText() {
            return "Remove TestNG import";
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            String text = tc.getWorkingCopy().getText();
            int startIndex = 0;
            while (true) {
                int runTestNG = text.indexOf("@run testng", startIndex);
                if (runTestNG == (-1)) return ;
                tc.getWorkingCopy().rewriteInComment(runTestNG, "@run testng".length(), "@run junit");
                startIndex = runTestNG + "@run testng".length();
            }
        }

    }
}
