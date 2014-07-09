/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.conf;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An annotation to denote {@link AccumuloConfiguration} {@link Property} keys, whose values should be interpolated with system properties.
 * 
 * Interpolated items need to be careful, as JVM properties could be updates and we may want that propogated when those changes occur. Currently only
 * VFS_CLASSLOADER_CACHE_DIR, which isn't ZK mutable, is interpolated, so this shouldn't be an issue as java.io.tmpdir also shouldn't be changing.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@interface SystemOverride {

}