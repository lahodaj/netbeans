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
package org.netbeans.modules.debugger.jpda.remote;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ByteValue;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import java.beans.PropertyVetoException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.netbeans.modules.debugger.jpda.EditorContextBridge;
import org.netbeans.modules.debugger.jpda.jdi.ArrayReferenceWrapper;
import org.netbeans.modules.debugger.jpda.jdi.ArrayTypeWrapper;
import org.netbeans.modules.debugger.jpda.jdi.ClassNotPreparedExceptionWrapper;
import org.netbeans.modules.debugger.jpda.jdi.ClassTypeWrapper;
import org.netbeans.modules.debugger.jpda.jdi.InternalExceptionWrapper;
import org.netbeans.modules.debugger.jpda.jdi.MirrorWrapper;
import org.netbeans.modules.debugger.jpda.jdi.ObjectCollectedExceptionWrapper;
import org.netbeans.modules.debugger.jpda.jdi.ObjectReferenceWrapper;
import org.netbeans.modules.debugger.jpda.jdi.ReferenceTypeWrapper;
import org.netbeans.modules.debugger.jpda.jdi.UnsupportedOperationExceptionWrapper;
import org.netbeans.modules.debugger.jpda.jdi.VMDisconnectedExceptionWrapper;
import org.netbeans.modules.debugger.jpda.jdi.VirtualMachineWrapper;
import org.netbeans.spi.debugger.jpda.EditorContext;
import org.openide.util.Exceptions;

/**
 * Services that manages uploaded classes into a remote JVM.
 *
 * @author Martin Entlicher
 */
public final class RemoteServices {

    private RemoteServices() {}

    /**
     * Upload a new remote class. The thread must already be locked for method
     * invocations before calling this method.
     * The class object has disabled collection.
     * Enable the collection when not used any more.
     * @param tr The thread to upload the class on.
     * @param rc The definition of the remote class.
     * @return The new uploaded class.
     * @throws InvalidTypeException
     * @throws ClassNotLoadedException
     * @throws IncompatibleThreadStateException
     * @throws InvocationException
     * @throws IOException
     * @throws PropertyVetoException
     * @throws InternalExceptionWrapper
     * @throws VMDisconnectedExceptionWrapper
     * @throws ObjectCollectedExceptionWrapper
     * @throws UnsupportedOperationExceptionWrapper
     * @throws ClassNotPreparedExceptionWrapper 
     */
    public static ClassObjectReference uploadClass(ThreadReference tr, ClassObjectReference context, RemoteClass rc) throws InvalidTypeException,
                                                                                              ClassNotLoadedException,
                                                                                              IncompatibleThreadStateException,
                                                                                              InvocationException,
                                                                                              IOException,
                                                                                              PropertyVetoException,
                                                                                              InternalExceptionWrapper,
                                                                                              VMDisconnectedExceptionWrapper,
                                                                                              ObjectCollectedExceptionWrapper,
                                                                                              UnsupportedOperationExceptionWrapper,
                                                                                              ClassNotPreparedExceptionWrapper {
        List<ObjectReference> reenableCollection = new ArrayList<>();
        VirtualMachine vm = MirrorWrapper.virtualMachine(tr);
        //this won't work when the context is load by the bootstrap CL:
        ClassLoaderReference classLoader = context.reflectedType().classLoader();
        ClassObjectReference classOption = loadClass(tr, classLoader, "java.lang.invoke.MethodHandles$Lookup$ClassOption", reenableCollection);

        try {
            if (classOption != null) {
                //target has support for MethodHandles.Lookup.defineHiddenClass:
                String targetClassName = context.reflectedType().name();
                int lastDot = targetClassName.lastIndexOf('.');
                String targetPackage;
                String injectorClassName;
                if (lastDot == (-1)) {
                    targetPackage = null;
                    injectorClassName = "$$WatchClassInject";
                } else {
                    targetPackage = targetClassName.substring(0, lastDot);
                    injectorClassName = targetPackage + ".$$WatchClassInject";
                }
                ClassObjectReference existingInjector = loadClass(tr, classLoader, injectorClassName, reenableCollection);
                if (existingInjector == null) {
                    Map<String, byte[]> bytecode = new HashMap<>();

                    class MemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
                        public MemoryFileManager(JavaFileManager fileManager) {
                            super(fileManager);
                        }
                        @Override
                        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
                            if (location == StandardLocation.CLASS_OUTPUT && kind == JavaFileObject.Kind.CLASS) {
                                try {
                                    return new SimpleJavaFileObject(new URI("mem://" + className + ".class"), JavaFileObject.Kind.CLASS) {
                                        @Override
                                        public OutputStream openOutputStream() throws IOException {
                                            return new ByteArrayOutputStream() {
                                                @Override
                                                public void close() throws IOException {
                                                    super.close();
                                                    bytecode.put(className, toByteArray());
                                                }
                                            };
                                        }
                                    };
                                } catch (URISyntaxException ex) {
                                    throw new IOException(ex);
                                }
                            }
                            return super.getJavaFileForOutput(location, className, kind, sibling);
                        }
                    }
                    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

                    try (StandardJavaFileManager sfm = compiler.getStandardFileManager(d -> {}, null, null);
                         MemoryFileManager mfm = new MemoryFileManager(sfm)) {
                        compiler.getTask(null, mfm, null, List.of("--release", "15", "-proc:none"), null, List.of(new SimpleJavaFileObject(URI.create("mem://$$WatchClassInject.java"), JavaFileObject.Kind.SOURCE) {
                            @Override
                            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                                return INJECT_CLASS.replace("$PACKAGE", targetPackage);
                            }
                        })).call();
                    }

                    for (Map.Entry<String, byte[]> e : bytecode.entrySet()) {
                        defineClass(vm, tr, classLoader, e.getKey(), e.getValue(), reenableCollection);
                    }

                    existingInjector = loadClass(tr, classLoader, injectorClassName, reenableCollection);

                    if (existingInjector == null) {
                        throw new IllegalStateException("Cannot define the class injector!");
                    }
                }
                ClassType existingInjectorType = (ClassType) existingInjector.reflectedType();
                Method inMethod = ClassTypeWrapper.concreteMethodByName(existingInjectorType, "injectClass", "(Ljava/lang/Class;[B)Ljava/lang/Class;");
                ArrayReference bytecode = createTargetBytes(vm, rc.bytes, new ByteValue[256], reenableCollection);
                return (ClassObjectReference) ClassTypeWrapper.invokeMethod(existingInjectorType, tr, inMethod, List.of(context, bytecode), ObjectReference.INVOKE_SINGLE_THREADED);
//                return (ClassObjectReference) existingInjector.invokeMethod(tr, inMethod, List.of(context, bytecode), ObjectReference.INVOKE_SINGLE_THREADED);
            } else {
                ClassObjectReference theUploadedClass = defineClass(vm, tr, classLoader, rc.name, rc.bytes, reenableCollection);
                // Initialize the class:
                ClassType bc = ((ClassType) theUploadedClass.reflectedType());
                if (!bc.isInitialized()) {
                    // Trying to initialize the class
                    ClassType theClass = getClass(vm, Class.class.getName());
                    // Call some method that will prepare the class:
                    Method aMethod = ClassTypeWrapper.concreteMethodByName(theClass, "getConstructors", "()[Ljava/lang/reflect/Constructor;");
                    ObjectReferenceWrapper.invokeMethod(theUploadedClass, tr, aMethod, Collections.<Value>emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
                }
                return theUploadedClass;
            }
        } finally {
            for (ObjectReference toReenable : reenableCollection) {
                try {
                    ObjectReferenceWrapper.enableCollection(toReenable); // We can dispose it now
                } catch (UnsupportedOperationExceptionWrapper uex) {}
            }
        }
    }

    private static ClassType getClass(VirtualMachine vm, String name) throws InternalExceptionWrapper,
                                                                             ObjectCollectedExceptionWrapper,
                                                                             VMDisconnectedExceptionWrapper {
        List<ReferenceType> classList = VirtualMachineWrapper.classesByName(vm, name);
        ReferenceType clazz = null;
        for (ReferenceType c : classList) {
            if (ReferenceTypeWrapper.classLoader(c) == null) {
                clazz = c;
                break;
            }
        }
        if (clazz == null && classList.size() > 0) {
            clazz = classList.get(0);
        }
        return (ClassType) clazz;
    }
    
    private static ArrayType getArrayClass(VirtualMachine vm, String name) throws InternalExceptionWrapper,
                                                                                  ObjectCollectedExceptionWrapper,
                                                                                  VMDisconnectedExceptionWrapper {
        List<ReferenceType> classList = VirtualMachineWrapper.classesByName(vm, name);
        ReferenceType clazz = null;
        for (ReferenceType c : classList) {
            if (ReferenceTypeWrapper.classLoader(c) == null) {
                clazz = c;
                break;
            }
        }
        return (ArrayType) clazz;
    }

    private static ClassObjectReference defineClass(VirtualMachine vm,
                                                    ThreadReference tr,
                                                    ClassLoaderReference classLoader,
                                                    String className,
                                                    byte[] classfile,
                                                    List<ObjectReference> reenableCollection) throws InvalidTypeException,
                                                                                                     ClassNotLoadedException,
                                                                                                     ClassNotPreparedExceptionWrapper,
                                                                                                     InternalExceptionWrapper,
                                                                                                     VMDisconnectedExceptionWrapper,
                                                                                                     ObjectCollectedExceptionWrapper,
                                                                                                     IncompatibleThreadStateException,
                                                                                                     InvocationException,
                                                                                                     UnsupportedOperationExceptionWrapper {
        ClassType classLoaderClass = (ClassType) ObjectReferenceWrapper.referenceType(classLoader);

        ArrayReference byteArray = createTargetBytes(vm, classfile, new ByteValue[256], reenableCollection);
        StringReference nameMirror = objectWithDisabledCollection(() -> VirtualMachineWrapper.mirrorOf(vm, className), reenableCollection);
        Method defineClass = ClassTypeWrapper.concreteMethodByName(classLoaderClass,
                                                                   "defineClass",
                                                                   "(Ljava/lang/String;[BII)Ljava/lang/Class;");
        return objectWithDisabledCollection(() -> (ClassObjectReference) ObjectReferenceWrapper.invokeMethod(
                classLoader, tr, defineClass,
                Arrays.asList(nameMirror, byteArray, vm.mirrorOf(0), vm.mirrorOf(classfile.length)),
                ObjectReference.INVOKE_SINGLE_THREADED), reenableCollection);
    }

    private static ArrayReference createTargetBytes(VirtualMachine vm, byte[] bytes,
                                                    ByteValue[] mirrorBytesCache,
                                                    List<ObjectReference> reenableCollection) throws InvalidTypeException,
                                                                                                     ClassNotLoadedException,
                                                                                                     InternalExceptionWrapper,
                                                                                                     VMDisconnectedExceptionWrapper,
                                                                                                     ObjectCollectedExceptionWrapper,
                                                                                                     IncompatibleThreadStateException,
                                                                                                     InvocationException,
                                                                                                     UnsupportedOperationExceptionWrapper {
        ArrayType bytesArrayClass = getArrayClass(vm, "byte[]");
        ArrayReference array = objectWithDisabledCollection(() -> ArrayTypeWrapper.newInstance(bytesArrayClass, bytes.length), reenableCollection);
        List<Value> values = new ArrayList<Value>(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            ByteValue mb = mirrorBytesCache[128 + b];
            if (mb == null) {
                mb = VirtualMachineWrapper.mirrorOf(vm, b);
                mirrorBytesCache[128 + b] = mb;
            }
            values.add(mb);
        }
        ArrayReferenceWrapper.setValues(array, values);
        return array;
    }

    private static <T extends ObjectReference> T objectWithDisabledCollection(CreateReference<T> provider,
                                                                              List<ObjectReference> reenableCollection) throws InternalExceptionWrapper,
                                                                                                                               VMDisconnectedExceptionWrapper,
                                                                                                                               ClassNotLoadedException,
                                                                                                                               IncompatibleThreadStateException,
                                                                                                                               InvalidTypeException,
                                                                                                                               InvocationException,
                                                                                                                               UnsupportedOperationExceptionWrapper,
                                                                                                                               ObjectCollectedExceptionWrapper {
        while (true) {
            T value = provider.create();
            try {
                ObjectReferenceWrapper.disableCollection(value);
                reenableCollection.add(value);
                return value;
            } catch (ObjectCollectedExceptionWrapper ocex) {
                // Collected too soon, try again...
            } catch (UnsupportedOperationExceptionWrapper uex) {
                // Hope it will not be GC'ed...
                return value;
            }
        }
    }

    private static ClassObjectReference loadClass(ThreadReference tr,
                                                  ClassLoaderReference classLoader,
                                                  String className,
                                                  List<ObjectReference> reenableCollection) throws InternalExceptionWrapper,
                                                                                                   VMDisconnectedExceptionWrapper,
                                                                                                   ClassNotPreparedExceptionWrapper,
                                                                                                   InvalidTypeException,
                                                                                                   ClassNotLoadedException,
                                                                                                   IncompatibleThreadStateException,
                                                                                                   InvocationException,
                                                                                                   UnsupportedOperationExceptionWrapper,
                                                                                                   ObjectCollectedExceptionWrapper {
        VirtualMachine vm = MirrorWrapper.virtualMachine(tr);
        ReferenceType jlClass = vm.classesByName("java.lang.Class").get(0);
        Method loadClass = jlClass.methodsByName("forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;").get(0);
        ObjectReference classNameMirror = objectWithDisabledCollection(() -> vm.mirrorOf(className), reenableCollection);

        try {
            return objectWithDisabledCollection(() -> (ClassObjectReference) jlClass.classObject().invokeMethod(tr, loadClass, Arrays.asList(classNameMirror, vm.mirrorOf(true), classLoader), ObjectReference.INVOKE_SINGLE_THREADED),
                                                reenableCollection);
        } catch (ClassNotLoadedException | IncompatibleThreadStateException | InvalidTypeException | InvocationException ex) {
            return null;
        }
    }

    interface CreateReference<T extends ObjectReference> {
        public T create() throws InternalExceptionWrapper, VMDisconnectedExceptionWrapper, ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException, InvocationException, UnsupportedOperationExceptionWrapper, ObjectCollectedExceptionWrapper;
    }

    private static final String INJECT_CLASS =
            """
            package $PACKAGE;

            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodHandles.Lookup;
            import java.lang.invoke.MethodHandles.Lookup.ClassOption;

            class $$WatchClassInject {
                public static Class<?> injectClass(Class<?> target, byte[] data) throws IllegalAccessException {
                    Lookup l = MethodHandles.lookup();
                    return MethodHandles.privateLookupIn(target, l).defineHiddenClass(data, true, ClassOption.NESTMATE).lookupClass();
                }
            }
            """;
}
