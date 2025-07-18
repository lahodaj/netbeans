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
package org.openjdk.compute.disabled.modules;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.netbeans.api.sendopts.CommandException;
import org.netbeans.spi.project.support.ant.PropertyEvaluator;
import org.netbeans.spi.project.support.ant.PropertyProvider;
import org.netbeans.spi.project.support.ant.PropertyUtils;
import org.netbeans.spi.sendopts.Arg;
import org.netbeans.spi.sendopts.ArgsProcessor;
import org.netbeans.spi.sendopts.Description;
import org.netbeans.spi.sendopts.Env;
import org.openide.*;
import org.openide.modules.Dependency;
import org.openide.modules.ModuleInfo;
import org.openide.util.EditableProperties;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

public class ComputeRequiredModules implements ArgsProcessor {

    private static final Set<String> BASE_CNB = new HashSet<>(Arrays.asList(
        "org.netbeans.core.startup",
        "org.netbeans.core.startup.base"
//        "org.netbeans.bootstrap" //XXX: this brings in the launcher, and missing launchers fail during platform app creation
    ));

    @Override
    public void process(Env env) throws CommandException {
        if (targetProperties != null) {
            try {
                computeDependencies();
            } catch (IOException ex) {
                ex.printStackTrace();
                throw (CommandException) new CommandException(1).initCause(ex);
            }
        }
    }

    @Arg(longName="compute-disabled-modules")
    @Description(shortDescription="#DESC_ComputeDisabledModules")
    @NbBundle.Messages("DESC_ComputeDisabledModules=Compue and set disabled modules")
    public String targetProperties;

    @Arg(longName="root-modules")
    @Description(shortDescription="#DESC_RootModules")
    @NbBundle.Messages("DESC_RootModules=Root modules")
    public String rootModules;

    @Arg(longName="baseline-extension")
    @Description(shortDescription="#DESC_BaselineExtension")
    @NbBundle.Messages("DESC_BaselineExtension=Baseline extension")
    public String baselineExtension;

    private void computeDependencies() throws IOException {
        Set<String> rootModulesSet = new HashSet<>(Arrays.asList(rootModules.trim().split(",")));
        Set<ModuleInfo> todo = new HashSet<>();
        Map<String, ModuleInfo> codeNameBase2ModuleInfo = new HashMap<>();
        Map<String, Set<String>> capability2Modules = new HashMap<>();

        for (ModuleInfo mi : Lookup.getDefault().lookupAll(ModuleInfo.class)) {
            codeNameBase2ModuleInfo.put(mi.getCodeNameBase(), mi);
            Arrays.asList(mi.getProvides()).forEach(p -> capability2Modules.computeIfAbsent(p, b -> new HashSet<>()).add(mi.getCodeNameBase()));
            if (rootModulesSet.contains(mi.getCodeNameBase())) {
                rootModulesSet.remove(mi.getCodeNameBase());
                todo.add(mi);
            }
        }

        if (!rootModulesSet.isEmpty()) {
            throw new IllegalStateException("not found: " + rootModulesSet);
        }

        Set<ModuleInfo> allDependencies = new HashSet<>();
        Set<String> seenNeeds = new HashSet<>();
        Set<String> seenRequires = new HashSet<>();
        Set<String> seenRecommends = new HashSet<>();
        Map<String, Set<String>> requestors = new HashMap<>();
        
        while (!todo.isEmpty()) {
            ModuleInfo currentModule = todo.iterator().next();

            todo.remove(currentModule);
            
            if (allDependencies.add(currentModule)) {
                for (Dependency d : currentModule.getDependencies()) {
                    switch (d.getType()) {
                        case Dependency.TYPE_MODULE:
                            String depName = d.getName();
                            int slash = depName.indexOf("/");

                            if (slash != (-1)) {
                                depName = depName.substring(0, slash);
                            }

                            ModuleInfo dependency = codeNameBase2ModuleInfo.get(depName);
                            if (dependency == null) {
                                System.err.println("cannot find module: " + depName);
                            } else {
                                add2TODO(currentModule, dependency, todo, requestors);
                            }
                            break;
                        case Dependency.TYPE_NEEDS:
                            if (seenNeeds.add(d.getName())) {
                                Set<String> fullfillingModules = capability2Modules.get(d.getName());
                                if (fullfillingModules == null) {
                                    System.err.println("module: " + currentModule.getCodeNameBase() + ", needs capability: '" + d.getName() + "', but there are no modules providing this capability");
                                } else if (fullfillingModules.size() == 1) {
                                    add2TODO(currentModule, codeNameBase2ModuleInfo.get(fullfillingModules.iterator().next()), todo, requestors);
                                } else {
                                    System.err.println("module: " + currentModule.getCodeNameBase() + ", needs capability: '" + d.getName() + "', modules that provide that capability are: " + fullfillingModules);
                                }
                            }
                            break;
                        case Dependency.TYPE_REQUIRES:
                            if (seenRequires.add(d.getName())) {
                                Set<String> fullfillingModules = capability2Modules.get(d.getName());
                                if (fullfillingModules == null) {
                                    System.err.println("module: " + currentModule.getCodeNameBase() + ", needs capability: '" + d.getName() + "', but there are no modules providing this capability");
                                } else if (fullfillingModules.size() == 1) {
                                    add2TODO(currentModule, codeNameBase2ModuleInfo.get(fullfillingModules.iterator().next()), todo, requestors);
                                } else {
                                    System.err.println("module: " + currentModule.getCodeNameBase() + ", requires capability: '" + d.getName() + "', modules that provide that capability are: " + fullfillingModules);
                                }
                            }
                            break;
                        case Dependency.TYPE_RECOMMENDS:
                            if (seenRecommends.add(d.getName())) {
                                Set<String> fullfillingModules = capability2Modules.get(d.getName());
                                System.err.println("module: " + currentModule.getCodeNameBase() + ", recommends capability: '" + d.getName() + "', modules that provide that capability are: " + fullfillingModules);
                            }
                            break;
                        case Dependency.TYPE_JAVA:
                            break;
                        default:
                            System.err.println("unhandled dependency: " + d);
                    }
                }
            }
        }

        Set<String> requiredCNBBases = allDependencies.stream().map(mi -> mi.getCodeNameBase()).collect(Collectors.toSet());
        
        String disabledModules = filterModulesToString(codeNameBase2ModuleInfo, cnbb -> !(requiredCNBBases.contains(cnbb) || rootModulesSet.contains(cnbb)));

        EditableProperties props = new EditableProperties(false);

        if (Files.isReadable(Paths.get(targetProperties))) {
            try (InputStream in = new FileInputStream(targetProperties)) {
                props.load(in);
            }
        }

        if (baselineExtension != null) {
            props.put("disabled.modules", disabledModules);
            EditableProperties baselineExtensionProperties = new EditableProperties(false);

            try (InputStream in = new FileInputStream(baselineExtension + "/nbcode/nbproject/platform.properties")) {
                baselineExtensionProperties.load(in);
            }

            Set<String> baselineDisabledModules = new HashSet<>(Arrays.asList(baselineExtensionProperties.get("disabled.modules").split("[ \n]*,[ \n]*")));
            String[] clusterPaths = clusterPaths();
            Predicate<String> baselineModuleExists = cnb -> moduleExistsInClusters(cnb, clusterPaths);
            String zipDisabledModules = filterModulesToString(codeNameBase2ModuleInfo, cnbb -> !((requiredCNBBases.contains(cnbb) && (!baselineModuleExists.test(cnbb) || baselineDisabledModules.contains(cnbb))) || rootModulesSet.contains(cnbb)));

            props.put("zip.disabled.modules", zipDisabledModules);
        }

        try (OutputStream out = new FileOutputStream(targetProperties)) {
            props.store(out);
        }

        LifecycleManager.getDefault().exit();
    }

    private void add2TODO(ModuleInfo requestor, ModuleInfo toAdd, Set<ModuleInfo> todo, Map<String, Set<String>> requestors) {
        todo.add(toAdd);
        requestors.computeIfAbsent(toAdd.getCodeNameBase(), x -> new HashSet<>()).add(requestor.getCodeNameBase());
    }

    private String filterModulesToString(Map<String, ModuleInfo> allModules, Predicate<String> filter) {
        return allModules.keySet()
                         .stream()
                         .filter(filter)
                         .collect(Collectors.joining(",", "", ""));
    }

    private static boolean moduleExistsInClusters(String cnb, String[] clusterPaths) {
        if (BASE_CNB.contains(cnb)) {
            return true;
        }

        for (String clusterPath : clusterPaths) {
            for (String subPath : new String[] {"lib", "core", "modules"}) {
                if (new File(clusterPath + "/" + subPath + "/" + cnb.replace('.', '-') + ".jar").canRead()) {
                    return true;
                }
            }
        }
        return false;
    }

    private String[] clusterPaths() {
        List<PropertyProvider> providers = new ArrayList<>();
        for (String path : new String[] {
            baselineExtension + "/nbcode/nbproject/platform.properties"
        }) {
            providers.add(PropertyUtils.propertiesFilePropertyProvider(new File(path)));
        }
        //TODO: basedir
        Map<String, String> hardcoded = new HashMap<>();
        File netbeansHome = new File(System.getProperty("netbeans.home")).getParentFile();
        hardcoded.put("nbplatform.active.dir", netbeansHome.getAbsolutePath());
        hardcoded.put("path.separator", ":");
        PropertyEvaluator eval = PropertyUtils.sequentialPropertyEvaluator(PropertyUtils.fixedPropertyProvider(hardcoded), providers.toArray(new PropertyProvider[0]));
        return eval.evaluate("${cluster.path}").split(":");
    }
}
