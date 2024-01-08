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
package org.netbeans.modules.java.file.launcher.hints;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.GlobalPathRegistry;
import org.netbeans.modules.java.file.launcher.SingleSourceFileUtil;
import org.netbeans.modules.java.file.launcher.queries.MultiSourceRootProvider;
import org.netbeans.modules.parsing.api.Snapshot;
import org.netbeans.modules.parsing.spi.Parser;
import org.netbeans.modules.parsing.spi.ParserResultTask;
import org.netbeans.modules.parsing.spi.Scheduler;
import org.netbeans.modules.parsing.spi.SchedulerEvent;
import org.netbeans.modules.parsing.spi.SchedulerTask;
import org.netbeans.modules.parsing.spi.TaskFactory;
import org.netbeans.spi.editor.hints.ChangeInfo;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.ErrorDescriptionFactory;
import org.netbeans.spi.editor.hints.Fix;
import org.netbeans.spi.editor.hints.HintsController;
import org.netbeans.spi.editor.hints.Severity;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;

public class UnregisteredRootHint extends ParserResultTask<Parser.Result> {

    @Override
    @Messages({
        "# {0} - source root",
        "ERR_UnregistredRoot={0} is not a registered as a Java root, some features may not work correctly"
    })
    public void run(Parser.Result result, SchedulerEvent event) {
        FileObject file = result.getSnapshot().getSource().getFileObject();
        MultiSourceRootProvider provider = Lookup.getDefault().lookup(MultiSourceRootProvider.class);
        ClassPath cp = provider.findClassPath(file, ClassPath.SOURCE);
        FileObject root = provider.getSourceRoot(file);

        if (cp == null || root == null) {
            return ;
        }

        for (ClassPath registeredCP : GlobalPathRegistry.getDefault().getPaths(ClassPath.SOURCE)) {
            if (registeredCP == cp) {
                setErrors(file, Collections.emptyList());
                return ;
            }
        }

        ErrorDescription err =
                ErrorDescriptionFactory.createErrorDescription(Severity.WARNING,
                                                               Bundle.ERR_UnregistredRoot(file.toURI().getPath()),
                                                               Arrays.asList(new FixImpl(root, cp)),
                                                               file,
                                                               0,
                                                               0);
        setErrors(file, Arrays.asList(err));
    }

    private static void setErrors(FileObject file, List<ErrorDescription> errs) {
        HintsController.setErrors(file, UnregisteredRootHint.class.getName(), errs);
    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public Class<? extends Scheduler> getSchedulerClass() {
        return Scheduler.EDITOR_SENSITIVE_TASK_SCHEDULER;
    }

    @Override
    public void cancel() {
    }

    private static final class FixImpl implements Fix {

        private final FileObject root;
        private final ClassPath cp;

        public FixImpl(FileObject root, ClassPath cp) {
            this.root = root;
            this.cp = cp;
        }

        @Override
        @Messages({
            "# {0} - source root",
            "FIX_RegisterRoot=Register {0} as a Java root"
        })
        public String getText() {
            return Bundle.FIX_RegisterRoot(root.toURI().getPath());
        }

        @Override
        public ChangeInfo implement() throws Exception {
            root.setAttribute(SingleSourceFileUtil.REGISTER_AS_JAVA_ROOT, true);
            GlobalPathRegistry.getDefault().register(ClassPath.SOURCE, new ClassPath[] {cp});
            return null;
        }

    }

    @MimeRegistration(mimeType="text/x-java", service=TaskFactory.class)
    public static final class FactoryImpl extends TaskFactory {

        @Override
        public Collection<? extends SchedulerTask> create(Snapshot snapshot) {
            return Arrays.asList(new UnregisteredRootHint());
        }

    }
}
