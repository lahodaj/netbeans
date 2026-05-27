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
package org.netbeans.modules.java.openjdk.jtreg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.event.ChangeListener;
import org.netbeans.spi.java.queries.CompilerOptionsQueryImplementation;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service=CompilerOptionsQueryImplementation.class, position=9999)
public class CompilerOptionsQueryImpl implements CompilerOptionsQueryImplementation {

    @Override
    public Result getOptions(FileObject file) {
        TestRootDescription rootDesc = TestRootDescription.findRootDescriptionFor(file);

        if (rootDesc == null) {
            return null;
        }

        if (file.isData()) {
            TagParser.Result tags = TagParser.parseTags(file);

            if (tags.getName2Tag().containsKey("test")) {
                List<Tag> modules = tags.getName2Tag().get("modules");

                if (modules != null) {
                    List<String> additionalOptions = new ArrayList<>();
                    for (Tag modulesTag : modules) {
                        String spec = modulesTag.getValue();
                        String[] packageSpec = spec.split("\\s+");

                        additionalOptions.addAll(EnablePreviewResult.ENABLE_PREVIEW_ARGS);

                        for (String onePackage : packageSpec) {
                            if (onePackage.indexOf('/') == (-1)) {
                                continue;
                            }

                            int colon = onePackage.indexOf(':');

                            if (colon != (-1)) {
                                onePackage = onePackage.substring(0, colon);
                            }

                            additionalOptions.add("--add-exports=" + onePackage + "=ALL-UNNAMED");
                        }
                    }

                    return new EnablePreviewResult(additionalOptions);
                }
            }
        }
        //enable preview in tests:
        return ENABLE_PREVIEW;
    }

    private static final Result ENABLE_PREVIEW = new EnablePreviewResult();

    private static final class EnablePreviewResult extends Result {

        private static final List<String> ENABLE_PREVIEW_ARGS =
                Collections.unmodifiableList(Arrays.asList("--enable-preview", "--add-modules", "ALL-MODULE-PATH"));

        private final List<String> args;

        private EnablePreviewResult() {
            this.args = ENABLE_PREVIEW_ARGS;
        }

        public EnablePreviewResult(List<String> args) {
            this.args = args;
        }

        @Override
        public List<? extends String> getArguments() {
            return args;
        }

        @Override
        public void addChangeListener(ChangeListener listener) {}

        @Override
        public void removeChangeListener(ChangeListener listener) {}

    }
}
