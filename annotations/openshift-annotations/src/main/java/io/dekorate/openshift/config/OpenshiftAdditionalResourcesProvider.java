/**
 * Copyright 2018 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **/
package io.dekorate.openshift.config;

import java.util.Arrays;
import java.util.List;

import io.dekorate.config.AdditionalResourcesProvider;

public class OpenshiftAdditionalResourcesProvider implements AdditionalResourcesProvider {

  @Override
  public int order() {
    return 2;
  }

  /**
   * @return resource names for Openshift annotations.
   */
  @Override
  public List<String> getResourceNames() {
    return Arrays.asList("application-openshift.properties",
        "application-openshift.yaml",
        "application-openshift.yml");
  }
}
