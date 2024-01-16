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
package org.netbeans.modules.java.file.launcher.actions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.event.ChangeListener;
import org.netbeans.api.extexecution.ExecutionDescriptor;
import org.netbeans.api.extexecution.ExecutionService;
import org.netbeans.modules.java.file.launcher.SingleSourceFileUtil;
import org.netbeans.api.extexecution.base.ExplicitProcessParameters;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.api.java.project.runner.JavaRunner;
import org.netbeans.api.java.queries.BinaryForSourceQuery;
import org.netbeans.api.java.queries.SourceForBinaryQuery;
import org.netbeans.modules.java.file.launcher.api.SourceLauncher;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.netbeans.spi.java.queries.BinaryForSourceQueryImplementation;
import org.netbeans.spi.java.queries.SourceForBinaryQueryImplementation;
import org.netbeans.spi.project.ActionProgress;
import org.netbeans.spi.project.ActionProvider;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.URLMapper;
import org.openide.modules.Places;
import org.openide.modules.SpecificationVersion;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.lookup.ServiceProvider;
import org.openide.windows.IOProvider;
import org.openide.windows.InputOutput;

/**
 * This class provides support to run a single Java file without a parent
 * project (JEP-330).
 *
 * @author Sarvesh Kesharwani
 */
@ServiceProvider(service = ActionProvider.class)
public final class SingleJavaSourceRunActionProvider implements ActionProvider {
    private static final SpecificationVersion SUPPORTS_MULTI_FILE_LAUNCHER = new SpecificationVersion("22");

    @Override
    public String[] getSupportedActions() {
        return new String[]{
            ActionProvider.COMMAND_RUN_SINGLE,
            ActionProvider.COMMAND_DEBUG_SINGLE
        };
    }

    @NbBundle.Messages({
        "CTL_SingleJavaFile=Running Single Java File"
    })
    @Override
    public void invokeAction(String command, Lookup context) throws IllegalArgumentException {
        FileObject fileObject = SingleSourceFileUtil.getJavaFileWithoutProjectFromLookup(context);
        if (fileObject == null) 
            return;

        File workDir = FileUtil.toFile(fileObject.getParent()); //XXX: better work dir

        JavaPlatform defaultPlatform = JavaPlatformManager.getDefault().getDefaultPlatform();

        if (SUPPORTS_MULTI_FILE_LAUNCHER.compareTo(defaultPlatform.getSpecification().getVersion()) > 0) {
            try {
                ClassPath sourcePath = ClassPath.getClassPath(fileObject, ClassPath.SOURCE);
                FileObject root = sourcePath.findOwnerRoot(fileObject);

                if (root == null) {
                    //warn...
                    return ;
                }

                File binaryRoot = getRunCacheFor(root);
                binaryRoot.mkdirs();
                ClassPath classPath = ClassPath.getClassPath(fileObject, JavaClassPathConstants.MODULE_CLASS_PATH);
                ClassPath runtimeClassPath = ClassPathSupport.createProxyClassPath(ClassPathSupport.createClassPath(binaryRoot.toURI().toURL()), classPath);
                ClassPath modulePath = ClassPath.getClassPath(fileObject, JavaClassPathConstants.MODULE_COMPILE_PATH);
                ClassPath runtimeModulePath = ClassPathSupport.createProxyClassPath(ClassPathSupport.createClassPath(binaryRoot.toURI().toURL()), modulePath);
                Map<String, Object> runProperties = new HashMap<>();

                runProperties.put(JavaRunner.PROP_EXECUTE_FILE, fileObject);
                runProperties.put(JavaRunner.PROP_WORK_DIR, workDir.getAbsolutePath());
                runProperties.put(JavaRunner.PROP_EXECUTE_CLASSPATH, runtimeClassPath);
                runProperties.put(JavaRunner.PROP_EXECUTE_MODULEPATH, runtimeModulePath);

                if (JavaRunner.isSupported(JavaRunner.QUICK_RUN, runProperties)) {
                    JavaRunner.execute(JavaRunner.QUICK_RUN, runProperties);
                } else {
                    //warn the user
                }
            } catch (IOException | UnsupportedOperationException ex) {
                Exceptions.printStackTrace(ex);
            }

            return ;
        }

        ExplicitProcessParameters params = ExplicitProcessParameters.buildExplicitParameters(context);
        InputOutput io = IOProvider.getDefault().getIO(Bundle.CTL_SingleJavaFile(), false);
        ActionProgress progress = ActionProgress.start(context);
        ExecutionDescriptor descriptor = new ExecutionDescriptor().
            controllable(true).
            frontWindow(true).
            preExecution(null).
            inputOutput(io).
            postExecution((exitCode) -> {
                progress.finished(exitCode == 0);
            });
        LaunchProcess process = invokeActionHelper(io, command, fileObject, params);
        ExecutionService exeService = ExecutionService.newService(
                    process,
                    descriptor, "Running Single Java File");
        Future<Integer> exitCode = exeService.run();
    }

    @Override
    public boolean isActionEnabled(String command, Lookup context) throws IllegalArgumentException {
        FileObject fileObject = SingleSourceFileUtil.getJavaFileWithoutProjectFromLookup(context);
        return fileObject != null;
    }
    
    final LaunchProcess invokeActionHelper (InputOutput io, String command, FileObject fo, ExplicitProcessParameters params) {
        JPDAStart start = ActionProvider.COMMAND_DEBUG_SINGLE.equals(command) ?
                new JPDAStart(io, fo) : null;
        return new LaunchProcess(fo, start, params);
    }

    private static File getRunCacheRoot() {
        return Places.getCacheSubdirectory("run");
    }

    private static File getRunCacheFor(FileObject root) {
        File cacheRoot = getRunCacheRoot();
        File segments = new File(cacheRoot, "segments");
        Properties mapping = new Properties();

        if (segments.canRead()) {
            try (InputStream in = new FileInputStream(segments)) {
                mapping.load(in);
            } catch (IOException ex) {
                LOG.log(Level.FINE, null, ex);
            }
        }
        String key = root.toURI().toString();
        String segment = mapping.getProperty(key);

        if (segment != null) {
            return new File(cacheRoot, segment);
        }

        String last = mapping.getProperty("next", "0");
        int lastIndex = Integer.parseInt(last);

        segment = "s" + lastIndex++;
        mapping.setProperty("next", String.valueOf(lastIndex));
        mapping.setProperty(key, segment);
        try (OutputStream out = new FileOutputStream(segments)) {
            mapping.store(out, "");
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }

        File result = new File(cacheRoot, segment);

        result.mkdirs();

        return result;
    }

    private static final Logger LOG = Logger.getLogger(SingleJavaSourceRunActionProvider.class.getName());

    @ServiceProvider(service=SourceForBinaryQueryImplementation.class)
    public static final class SourceForBinaryQueryImpl implements SourceForBinaryQueryImplementation {

        @Override
        public SourceForBinaryQuery.Result findSourceRoots(URL binaryRoot) {
            try {
                File cacheRoot = getRunCacheRoot();
                File binaryRootFile = new File(binaryRoot.toURI());

                if (!binaryRootFile.getAbsolutePath().startsWith(cacheRoot.getAbsolutePath())) {
                    return null;
                }

                File segments = new File(cacheRoot, "segments");
                Properties mapping = new Properties();

                if (segments.canRead()) {
                    try (InputStream in = new FileInputStream(segments)) {
                        mapping.load(in);
                    } catch (IOException ex) {
                        LOG.log(Level.FINE, null, ex);
                    }
                }

                String segment = binaryRootFile.getName();
                for (String source : mapping.stringPropertyNames()) {
                    if (segment.equals(mapping.getProperty(source))) {
                        FileObject root = URLMapper.findFileObject(new URL(source));
                        return new SourceForBinaryQuery.Result() {
                            @Override
                            public FileObject[] getRoots() {
                                return new FileObject[] {
                                    root
                                };
                            }
                            @Override
                            public void addChangeListener(ChangeListener l) {
                            }
                            @Override
                            public void removeChangeListener(ChangeListener l) {
                            }
                        };
                    }
                }

                return null;
            } catch (URISyntaxException ex) {
                LOG.log(Level.FINE, null, ex);
                return null;
            } catch (MalformedURLException ex) {
                LOG.log(Level.FINE, null, ex);
                return null;
            }
        }
        
    }

    @ServiceProvider(service=BinaryForSourceQueryImplementation.class)
    public static final class BinaryForSourceQueryImpl implements BinaryForSourceQueryImplementation {

        @Override
        public BinaryForSourceQuery.Result findBinaryRoots(URL sourceRoot) {
            try {
                FileObject root = URLMapper.findFileObject(sourceRoot);

                if (root == null || !SourceLauncher.isSourceLauncherFile(root)) {
                    return null;
                }

                File runCacheFor = getRunCacheFor(root);
                URL runCacheForURL = runCacheFor.toURI().toURL();
                return new BinaryForSourceQuery.Result() {
                    @Override
                    public URL[] getRoots() {
                        return new URL[] {
                            runCacheForURL
                        };
                    }

                    @Override
                    public void addChangeListener(ChangeListener l) {
                    }

                    @Override
                    public void removeChangeListener(ChangeListener l) {
                    }
                };
            } catch (MalformedURLException ex) {
                LOG.log(Level.FINE, null, ex);
                return null;
            }
        }

    }

}
