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
package org.netbeans.api.gototest;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.spi.gototest.TestLocator;
import org.netbeans.spi.gototest.TestLocator.LocationListener;
import org.netbeans.spi.gototest.TestLocator.LocationResult;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

/**
 * Find one or multiple test files for a source file,
 * and one or multiple source files for a test file.
 *
 * @since 1.57
 */
public class TestOppositesLocator {

    public static TestOppositesLocator getDefault() {
        return new TestOppositesLocator();
    }

    /**
     * Given the file and position in the file, if the:
     * <ul>
     *     <li> given file is a source file, find corresponding test file or test files, if exist.</li>
     *     <li> given file is a test file, find corresponding source file or source files, if exist.</li>
     * </ul>
     *
     * @param fo the file for which the opposites should be found
     * @param caretOffset position in the file, or {@code -1} if unknown
     * @return a result describing either an error, or a possibly empty list of locations found;
     *         note one of {@code errorMessage} and {@code locations} is always {@code null},
     *         and one always non-{@code null}.
     */
    @NbBundle.Messages("No_Test_Or_Tested_Class_Found=No Test or Tested class found")
    public LocatorResult findOpposites(FileObject fo, int caretOffset) {
        TestLocator.FileType currentFileType = getCurrentFileType(fo);
        if(currentFileType == TestLocator.FileType.NEITHER) {
            return new LocatorResult(Bundle.No_Test_Or_Tested_Class_Found(), null);
        }
        else {
            return new LocatorResult(null, Collections.unmodifiableList(populateLocationResults(fo, caretOffset)));
        }
    }

    private List<NamedLocation> populateLocationResults(FileObject fo, int caretOffset) {
        Map<LocationResult, String> locationResults = new HashMap<LocationResult, String>();

        Collection<? extends TestLocator> locators = Lookup.getDefault()
                                                           .lookupAll(TestLocator.class)
                                                           .stream()
                                                           .filter(tl -> tl.appliesTo(fo))
                                                           .collect(Collectors.toList());

        CountDownLatch allDone = new CountDownLatch(locators.size());

        for (TestLocator locator : locators) {
            if (locator.appliesTo(fo)) {
                if (locator.asynchronous()) {
                    locator.findOpposite(fo, caretOffset, new LocationListener() {
                        @Override
                        public void foundLocation(FileObject fo, TestLocator.LocationResult location) {
                            if (location != null) {
                                FileObject fileObject = location.getFileObject();
                                if(fileObject == null) {
                                    String msg = location.getErrorMessage();
                                    if (msg != null) {
                                        DialogDisplayer.getDefault().notify(
                                                new NotifyDescriptor.Message(msg, NotifyDescriptor.INFORMATION_MESSAGE));
                                    }
                                } else {
                                    locationResults.put(location, fileObject.getName());
                                }
                            }
                            allDone.countDown();
                        }
                    });
                } else {
                    TestLocator.LocationResult opposite = locator.findOpposite(fo, caretOffset);

                    if (opposite != null) {
                        FileObject fileObject = opposite.getFileObject();
                        if (fileObject == null) {
                            String msg = opposite.getErrorMessage();
                            if (msg != null) {
                                DialogDisplayer.getDefault().notify(
                                        new NotifyDescriptor.Message(msg, NotifyDescriptor.INFORMATION_MESSAGE));
                            }
                        } else {
                            locationResults.put(opposite, fileObject.getName());
                        }
                    }

                    allDone.countDown();
                }
            }
        }

        try {
            allDone.await();
        } catch (InterruptedException ex) {
            Exceptions.printStackTrace(ex);
        }

        return locationResults.entrySet()
                              .stream()
                              .map(e -> new NamedLocation(e.getKey(),
                                                          e.getValue()))
                              .collect(Collectors.toList());
    }

    private TestLocator getLocatorFor(FileObject fo) {
        Collection<? extends TestLocator> locators = Lookup.getDefault().lookupAll(TestLocator.class);
        for (TestLocator locator : locators) {
            if (locator.appliesTo(fo)) {
                return locator;
            }
        }
        
        return null;
    }
    
    private TestLocator.FileType getFileType(FileObject fo) {
        TestLocator locator = getLocatorFor(fo);
        if (locator != null) {
            return locator.getFileType(fo);
        }
        
        return TestLocator.FileType.NEITHER;
    }
    
    private TestLocator.FileType getCurrentFileType(FileObject fo) {
        return (fo != null) ? getFileType(fo) : TestLocator.FileType.NEITHER;
    }

    /**
     * A description of the found opposite files. Exactly one of {@code errorMessage}
     * {@code locations} will be non-null;
     */
    public static final class LocatorResult {
        public final String errorMessage;
        public final Collection<NamedLocation> locations;

        private LocatorResult(String errorMessage, Collection<NamedLocation> locations) {
            if (errorMessage == null && locations == null) {
                throw new IllegalArgumentException("Both errorMessage and locations is null!");
            }
            this.errorMessage = errorMessage;
            this.locations = locations;
        }
    }

    /**
     * A location and a name of the location.
     */
    public static final class NamedLocation {
        public @NonNull final LocationResult location;
        public @NonNull final String displayName;

        private NamedLocation(LocationResult location, String displayName) {
            this.location = location;
            this.displayName = displayName;
        }
    }
}
