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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.TreePathHandle;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.java.hints.ErrorDescriptionFactory;
import org.netbeans.spi.java.hints.Hint;
import org.netbeans.spi.java.hints.HintContext;
import org.netbeans.spi.java.hints.JavaFix;
import org.netbeans.spi.java.hints.TriggerTreeKind;
import org.openide.util.NbBundle;

@Hint(displayName = "#DN_JUnitEnforceSourceOrder", description = "#DESC_JUnitEnforceSourceOrder", category = "general")
@NbBundle.Messages({
    "DN_JUnitEnforceSourceOrder=JUnit enforce source order",
    "DESC_JUnitEnforceSourceOrder=JUnit enforce source order",
    "ERR_JUnitEnforceSourceOrder=Set order annotation"
})
public class JUnitEnforceSourceOrder {

    @TriggerTreeKind(Tree.Kind.CLASS)
    public static ErrorDescription setOrder(HintContext ctx) {
        List<TreePathHandle> testMethods = new ArrayList<>();
        ClassTree clazz = (ClassTree) ctx.getPath().getLeaf();

        for (Tree member : clazz.getMembers()) {
            if (member.getKind() == Kind.METHOD) {
                TreePath memberPath = new TreePath(ctx.getPath(), member);
                Element el = ctx.getInfo().getTrees().getElement(memberPath);

                for (AnnotationMirror am : el.getAnnotationMirrors()) {
                    if (am.getAnnotationType().toString().equals("org.junit.jupiter.api.Test")) {
                        testMethods.add(TreePathHandle.create(memberPath, ctx.getInfo()));
                    }
                }
            }
        }

        if (!testMethods.isEmpty()) {
            return ErrorDescriptionFactory.forName(ctx, ctx.getPath(), Bundle.ERR_JUnitEnforceSourceOrder(), new EnforceOrder(ctx.getInfo(), ctx.getPath(), testMethods).toEditorFix());
        }

        return null;
    }

    private static final class EnforceOrder extends JavaFix {

        private final List<TreePathHandle> testMethods;

        public EnforceOrder(CompilationInfo info, TreePath tp, List<TreePathHandle> testMethods) {
            super(info, tp);
            this.testMethods = testMethods;
        }

        @Override
        protected String getText() {
            return "Add @Order annotations";
        }

        @Override
        protected void performRewrite(TransformationContext tc) throws Exception {
            TreeMaker make = tc.getWorkingCopy().getTreeMaker();
            int group = 0;

            for (TreePathHandle toAugment : testMethods) {
                TreePath member = toAugment.resolve(tc.getWorkingCopy());

                if (member == null) {
                    //TODO: log?
                    continue;
                }

                MethodTree method = (MethodTree) member.getLeaf();
                AnnotationTree orderAnnotation = make.Annotation(make.QualIdent("org.junit.jupiter.api.Order"), List.of(make.Literal(group)));

                insertAnnotation(tc.getWorkingCopy(), method.getModifiers(), orderAnnotation);
                group++;
            }

            ClassTree clazz = (ClassTree) tc.getPath().getLeaf();
            AnnotationTree methodOrderAnnotation = make.Annotation(make.QualIdent("org.junit.jupiter.api.TestMethodOrder"), List.of(make.MemberSelect(make.QualIdent("org.junit.jupiter.api.MethodOrderer.OrderAnnotation"), "class")));

            insertAnnotation(tc.getWorkingCopy(), clazz.getModifiers(), methodOrderAnnotation);
        }

        private void insertAnnotation(WorkingCopy wc, ModifiersTree mods, AnnotationTree nue) {
            TreeMaker make = wc.getTreeMaker();
            int insertPoint = 0;
            String newAnnotation = nue.getAnnotationType().toString();

            mods = (ModifiersTree) wc.resolveRewriteTarget(mods);

            for (AnnotationTree existing : mods.getAnnotations()) {
                if (existing.getAnnotationType().toString().compareTo(newAnnotation) >= 0) {
                    break;
                }
            }

            wc.rewrite(mods, make.insertModifiersAnnotation(mods, insertPoint, nue));
        }
    }
}
