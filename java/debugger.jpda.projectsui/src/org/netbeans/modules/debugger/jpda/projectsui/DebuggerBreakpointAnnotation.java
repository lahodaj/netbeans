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

package org.netbeans.modules.debugger.jpda.projectsui;

import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import org.netbeans.api.debugger.Breakpoint;
import org.netbeans.api.debugger.Breakpoint.HIT_COUNT_FILTERING_STYLE;
import org.netbeans.api.debugger.jpda.LineBreakpoint;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.spi.debugger.jpda.EditorContext;
import org.netbeans.spi.debugger.ui.BreakpointAnnotation;

import org.openide.ErrorManager;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.URLMapper;
import org.openide.text.Line;
import org.openide.text.Line.Part;
import org.openide.util.NbBundle;


/**
 * Debugger Breakpoint Annotation class.
 *
 * @author   Jan Jancura
 */
public class DebuggerBreakpointAnnotation extends BreakpointAnnotation {

    private final Line           line;
    private final String         type;
    private final Breakpoint breakpoint;


    DebuggerBreakpointAnnotation (String type, Line line, Breakpoint breakpoint) {
        this(type, line, null, breakpoint);
    }

    DebuggerBreakpointAnnotation (String type, Line line, Part part, Breakpoint breakpoint) {
        this.type = type;
        this.line = line;
        this.breakpoint = breakpoint;
        if (breakpoint instanceof LineBreakpoint lb && lb.getLambdaIndex() >= 0) {
            part = getLambdaSpan(line, lb);
        }
        attach (part != null ? part : line);
    }
    
    private Part getLambdaSpan(Line line, LineBreakpoint lb) {
        Part[] result = new Part[1];
        //TODO: performance!!
        try {
            FileObject file = URLMapper.findFileObject(new URL(lb.getURL()));
            JavaSource js = JavaSource.forFileObject(file);
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.PARSED);
                //TODO: span only!
                new TreePathScanner<Void, Void>() {
                    int idx = 0;
                    public Void visitLambdaExpression(LambdaExpressionTree tree, Void v) {
                        long startPos = cc.getTrees().getSourcePositions().getStartPosition(getCurrentPath().getCompilationUnit(), tree);
                        LineMap lm = cc.getCompilationUnit().getLineMap();
                        int startLine = (int) lm.getLineNumber(startPos) - 1;
                        if (startLine == line.getLineNumber()) {
                            if (idx == lb.getLambdaIndex()) {
                                long endPos = cc.getTrees().getSourcePositions().getEndPosition(getCurrentPath().getCompilationUnit(), tree);
                                int startColumn = (int) lm.getColumnNumber(startPos) - 1;
                                int endColumn = (int) lm.getColumnNumber(endPos) - 1;
                                result[0] = line.createPart(startColumn, endColumn - startColumn);
                            }
                            idx++;
                        }
                        return super.visitLambdaExpression(tree, v);
                    }
                }.scan(cc.getCompilationUnit(), null);
            }, true);
            return result[0];
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return null;
    }

    @Override
    public String getAnnotationType () {
        return type;
    }
    
    Line getLine () {
        return line;
    }
   
    @Override
    public String getShortDescription () {

        List<String> list = new LinkedList<String>();
        //add condition if available
        String condition = BreakpointAnnotationProvider.getCondition(breakpoint);
        if (!condition.trim().isEmpty()) {
            list.add(condition);
        }

        // add hit count if available
        HIT_COUNT_FILTERING_STYLE hitCountFilteringStyle = breakpoint.getHitCountFilteringStyle();
        if (null != hitCountFilteringStyle) {
            int hcf = breakpoint.getHitCountFilter();
            String tooltip;
            switch (hitCountFilteringStyle) {
                case EQUAL:
                    tooltip = NbBundle.getMessage(DebuggerBreakpointAnnotation.class, "TOOLTIP_HITCOUNT_EQUAL", hcf);
                    break;
                case GREATER:
                    tooltip = NbBundle.getMessage(DebuggerBreakpointAnnotation.class, "TOOLTIP_HITCOUNT_GREATER", hcf);
                    break;
                case MULTIPLE:
                    tooltip = NbBundle.getMessage(DebuggerBreakpointAnnotation.class, "TOOLTIP_HITCOUNT_MULTIPLE", hcf);
                    break;
                default:
                    throw new IllegalStateException("Unknown HitCountFilteringStyle: "+hitCountFilteringStyle);
            }
            list.add(tooltip);
        }

        String shortDesc = getShortDescriptionIntern();
        if (list.isEmpty()) {
            return shortDesc;
        }
        StringBuilder result = new StringBuilder();
        if (null != shortDesc) {
            result.append(shortDesc);
        }
        //append more information
        result.append("\n");
        result.append(NbBundle.getMessage(DebuggerBreakpointAnnotation.class, "TOOLTIP_CONDITION"));
        for (String text : list) {
            result.append("\n");
            result.append(text);
        }

        return result.toString();
    }

    private String getShortDescriptionIntern () {
        if (type.endsWith("_broken")) {
            if (breakpoint.getValidity() == Breakpoint.VALIDITY.INVALID) {
                String msg = breakpoint.getValidityMessage();
                return NbBundle.getMessage(DebuggerBreakpointAnnotation.class,
                                           "TOOLTIP_BREAKPOINT_BROKEN_INVALID", msg);
            }
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_BREAKPOINT_BROKEN"); // NOI18N
        }
        if (type.endsWith("_stroke")) {
            return NbBundle.getMessage(DebuggerBreakpointAnnotation.class,
                                       "TOOLTIP_BREAKPOINT_STROKE");
        }
        if (type == EditorContext.BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_BREAKPOINT"); // NOI18N
        } else 
        if (type == EditorContext.DISABLED_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_DISABLED_BREAKPOINT"); // NOI18N
        } else 
        if (type == EditorContext.CONDITIONAL_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_CONDITIONAL_BREAKPOINT"); // NOI18N
        } else
        if (type == EditorContext.DISABLED_CONDITIONAL_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_DISABLED_CONDITIONAL_BREAKPOINT"); // NOI18N
        } else
        if (type == EditorContext.FIELD_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_FIELD_BREAKPOINT"); // NOI18N
        } else
        if (type == EditorContext.DISABLED_FIELD_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_DISABLED_FIELD_BREAKPOINT"); // NOI18N
        } else
        if (type == EditorContext.METHOD_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_METHOD_BREAKPOINT"); // NOI18N
        } else
        if (type == EditorContext.DISABLED_METHOD_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage 
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_DISABLED_METHOD_BREAKPOINT"); // NOI18N
        } else
        if (type == EditorContext.CLASS_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_CLASS_BREAKPOINT"); // NOI18N
        } else
        if (type == EditorContext.DISABLED_CLASS_BREAKPOINT_ANNOTATION_TYPE) {
            return NbBundle.getMessage
                (DebuggerBreakpointAnnotation.class, "TOOLTIP_DISABLED_CLASS_BREAKPOINT"); // NOI18N
        }
        ErrorManager.getDefault().notify(ErrorManager.INFORMATIONAL, new IllegalStateException("Unknown breakpoint type '"+type+"'."));
        return null;
    }
    
    @Override
    public Breakpoint getBreakpoint() {
        return breakpoint;
    }
    
}
