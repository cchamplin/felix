/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.karaf.features.management;

import javax.management.openmbean.TabularData;

public interface FeaturesServiceMBean {

    TabularData getFeatures() throws Exception;

    TabularData getRepositories() throws Exception;

    void addRepository(String url) throws Exception;

    void removeRepositroy(String url) throws Exception;

    void installFeature(String name) throws Exception;

    void installFeature(String name, String version) throws Exception;

    void uninstallFeature(String name) throws Exception;

    void uninstallFeature(String name, String version) throws Exception;

    String FEATURE_NAME = "Name";

    String FEATURE_VERSION = "Version";

    String FEATURE_DEPENDENCIES = "Dependencies";

    String FEATURE_BUNDLES = "Bundles";

    String FEATURE_CONFIGURATIONS = "Configurations";

    String FEATURE_INSTALLED = "Installed";

    String FEATURE_CONFIG_PID = "Pid";
    String FEATURE_CONFIG_ELEMENTS = "Elements";
    String FEATURE_CONFIG_ELEMENT_KEY = "Key";
    String FEATURE_CONFIG_ELEMENT_VALUE = "Value";

    /**
     * The type of the event which is emitted for features events
     */
    String FEATURE_EVENT_TYPE = "org.apache.felix.karaf.features.featureEvent";

    String FEATURE_EVENT_EVENT_TYPE = "Type";

    String FEATURE_EVENT_EVENT_TYPE_INSTALLED = "Installed";

    String FEATURE_EVENT_EVENT_TYPE_UNINSTALLED = "Uninstalled";

    /**
     * The item names in the CompositeData representing a feature
     */
    String[] FEATURE = { FEATURE_NAME, FEATURE_VERSION, FEATURE_DEPENDENCIES, FEATURE_BUNDLES,
                         FEATURE_CONFIGURATIONS, FEATURE_INSTALLED };

    String[] FEATURE_IDENTIFIER = { FEATURE_NAME, FEATURE_VERSION };

    String[] FEATURE_CONFIG = { FEATURE_CONFIG_PID, FEATURE_CONFIG_ELEMENTS };

    String[] FEATURE_CONFIG_ELEMENT = { FEATURE_CONFIG_ELEMENT_KEY, FEATURE_CONFIG_ELEMENT_VALUE };

    /**
     * The item names in the CompositeData representing the event raised for
     * feature events within the OSGi container by this bean
     */
    String[] FEATURE_EVENT = { FEATURE_NAME, FEATURE_VERSION, FEATURE_EVENT_EVENT_TYPE };


    String REPOSITORY_URI = "Uri";

    String REPOSITORY_REPOSITORIES = "Repositories";

    String REPOSITORY_FEATURES = "Features";

    /**
     * The type of the event which is emitted for repositories events
     */
    String REPOSITORY_EVENT_TYPE = "org.apache.felix.karaf.features.repositoryEvent";

    String REPOSITORY_EVENT_EVENT_TYPE = "Type";

    String REPOSITORY_EVENT_EVENT_TYPE_ADDED = "Added";

    String REPOSITORY_EVENT_EVENT_TYPE_REMOVED = "Removed";

    /**
     * The item names in the CompositeData representing a feature
     */
    String[] REPOSITORY = { REPOSITORY_URI,  REPOSITORY_REPOSITORIES, REPOSITORY_FEATURES };

    /**
     * The item names in the CompositeData representing the event raised for
     * feature events within the OSGi container by this bean
     */
    String[] REPOSITORY_EVENT = { REPOSITORY_URI, REPOSITORY_EVENT_EVENT_TYPE };

}
