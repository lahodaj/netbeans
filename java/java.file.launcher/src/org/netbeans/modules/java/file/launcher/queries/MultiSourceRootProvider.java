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
package org.netbeans.modules.java.file.launcher.queries;

import com.sun.source.tree.PackageTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.GlobalPathRegistry;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.lexer.TokenHierarchy;
import org.netbeans.api.lexer.TokenSequence;
import org.netbeans.api.queries.FileEncodingQuery;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service=ClassPathProvider.class, position=100_000)
public class MultiSourceRootProvider implements ClassPathProvider {

    //TODO: the cache will probably be never cleared, as the ClassPath/value refers to the key(?)
    private Map<FileObject, ClassPath> file2SourceCP = new WeakHashMap<>();
    private Map<FileObject, ClassPath> root2SourceCP = new WeakHashMap<>();

    @Override
    public ClassPath findClassPath(FileObject file, String type) {
        if (!ClassPath.SOURCE.equals(type)) {
            return null;
        }
        synchronized (this) {
            //XXX: what happens if there's a Java file in user's home???
            if (file.isData() && "text/x-java".equals(file.getMIMEType())) {
                return file2SourceCP.computeIfAbsent(file, f -> {
                    try {
                        String content = new String(file.asBytes(), FileEncodingQuery.getEncoding(file));
                        String packName = findPackage(content);
                        FileObject root = file.getParent();

                        if (packName != null) {
                            for (String packagePart : packName.split("\\.")) {
                                root = root.getParent();
                            }
                        }

                        return root2SourceCP.computeIfAbsent(root, r -> { //XXX: weak....
                            ClassPath srcCP = ClassPathSupport.createClassPath(r);
                            GlobalPathRegistry.getDefault().register(ClassPath.SOURCE, new ClassPath[] {srcCP});
                            return srcCP;
                        });
                    } catch (IOException ex) {
                        Exceptions.printStackTrace(ex);
                    }
                    return null;
                });
            } else {
                return root2SourceCP.get(file);
            }
        }
    }

    private static final Set<JavaTokenId> IGNORED_TOKENS = EnumSet.of(
        JavaTokenId.BLOCK_COMMENT,
        JavaTokenId.JAVADOC_COMMENT,
        JavaTokenId.LINE_COMMENT,
        JavaTokenId.WHITESPACE
    );

    static String findPackage(String fileContext) {
        TokenHierarchy<String> th = TokenHierarchy.create(fileContext, true, JavaTokenId.language(), IGNORED_TOKENS, null);
        TokenSequence<JavaTokenId> ts = th.tokenSequence(JavaTokenId.language());

        ts.moveStart();

        while (ts.moveNext()) {
            if (ts.token().id() == JavaTokenId.PACKAGE) {
                StringBuilder packageName = new StringBuilder();
                while (ts.moveNext() && (ts.token().id() == JavaTokenId.DOT || ts.token().id() == JavaTokenId.IDENTIFIER)) {
                    packageName.append(ts.token().text());
                }
                return packageName.toString();
            }
        }

        return null;
    }

    private static final class JFOImpl extends SimpleJavaFileObject {

        private final String content;

        public JFOImpl(String content) throws URISyntaxException {
            super(new URI("mem://Input.java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return content;
        }

    }
}
