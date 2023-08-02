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
package org.netbeans.modules.java.file.launcher;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.netbeans.api.annotations.common.CheckForNull;
import org.netbeans.modules.java.file.launcher.SingleSourceFileUtil;
import org.openide.filesystems.FileAttributeEvent;
import org.openide.filesystems.FileChangeAdapter;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;

/**
 *
 * @author lahvac
 */
public class SharedRootData {

    private static final Pattern ENABLE_PREVIEW_PATTERN = Pattern.compile("(^|[ \t])--enable-preview($|[ \t])");
    private static final Pattern SOURCE_PATTERN = Pattern.compile("(^|[ \t])--source[ \t]+([0-9]+(\\.[0-9]+)*)($|[ \t])");
    private static Map<FileObject, SharedRootData> root2Data = new HashMap<>();

    public static synchronized void ensureRootRegistered(FileObject root) {
        root2Data.computeIfAbsent(root, r -> new SharedRootData(r));
    }

    public static synchronized @CheckForNull SharedRootData getDataForRoot(FileObject root) {
        return root2Data.get(root);
    }

    private final FileObject root;
    private final Properties options = new Properties();
    private final FileChangeListener listener = new FileChangeAdapter() {
        @Override
        public void fileAttributeChanged(FileAttributeEvent fe) {
            Map<String, String> newProperties = new HashMap<>();

            addPropertiesFor(fe.getFile(), newProperties);
            setNewProperties(newProperties);
        }
    };

    private SharedRootData(FileObject root) {
        this.root = root;
        root.addRecursiveListener(listener);
        Enumeration<? extends FileObject> todo = root.getChildren(true);
        Map<String, String> newProperties = new HashMap<>();
        while (todo.hasMoreElements()) {
            FileObject current = todo.nextElement();
            addPropertiesFor(current, newProperties);
        }
        setNewProperties(newProperties);
    }

    private void addPropertiesFor(FileObject file, Map<String, String> newProperties) {
        if (file.isData() && "text/x-java".equals(file.getMIMEType())) {
            newProperties.put(FileUtil.getRelativePath(root, file), (String) file.getAttribute(SingleSourceFileUtil.FILE_VM_OPTIONS));
        }
    }

    private synchronized void setNewProperties(Map<String, String> newProperties) {
        if (newProperties.isEmpty()) {
            return ;
        }
        for (String key : newProperties.keySet()) {
            String value = newProperties.get(key);
            if (value == null) {
                options.remove(key);
            } else {
                options.setProperty(key, value);
            }
        }
        Map<String, String> joinedOptions = new HashMap<>();
        for (String key : options.stringPropertyNames()) {
            String value = options.getProperty(key);
            if (ENABLE_PREVIEW_PATTERN.matcher(value).find()) {
                joinedOptions.put("--enable-preview", null);
            }
            Matcher sourceMatcher = SOURCE_PATTERN.matcher(value);
            if (sourceMatcher.find()) {
                //TODO: merging sources!
                joinedOptions.put("--source", sourceMatcher.group(2));
            }
        }
        String joinedCommandLine = joinedOptions.entrySet().stream().map(e -> e.getKey() + (e.getValue() != null ? " " + e.getValue() : "")).collect(Collectors.joining(" "));
        try {
            if (!joinedCommandLine.equals(root.getAttribute(SingleSourceFileUtil.FILE_VM_OPTIONS))) {
                //TODO: reindex if changed?  listening on CompilerOptionsQuery.Result should do that automatically?
                root.setAttribute(SingleSourceFileUtil.FILE_VM_OPTIONS, joinedCommandLine);
            }
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }
    }

}
