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
package org.apache.accumulo.server.conf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.accumulo.fate.zookeeper.ZooCacheFactory;
import org.apache.accumulo.server.Accumulo;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.fs.VolumeManagerImpl;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

public class ZooConfiguration extends AccumuloConfiguration {
  private static final Logger log = Logger.getLogger(ZooConfiguration.class);
  
  private final AccumuloConfiguration parent;
  private static ZooConfiguration instance = null;
  private static String instanceId = null;
  private static ZooCache propCache = null;
  private final Map<String,String> fixedProps = Collections.synchronizedMap(new HashMap<String,String>());
  
  private ZooConfiguration(AccumuloConfiguration parent) {
    this.parent = parent;
  }
  
  synchronized public static ZooConfiguration getInstance(Instance inst, AccumuloConfiguration parent) {
    if (instance == null) {
      propCache = new ZooCacheFactory().getZooCache(inst.getZooKeepers(), inst.getZooKeepersSessionTimeOut());
      instance = new ZooConfiguration(parent);
      instanceId = inst.getInstanceID();
    }
    return instance;
  }
  
  synchronized public static ZooConfiguration getInstance(AccumuloConfiguration parent) {
    if (instance == null) {
      propCache = new ZooCacheFactory().getZooCache(parent.get(Property.INSTANCE_ZK_HOST), (int) parent.getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT));
      instance = new ZooConfiguration(parent);
      // InstanceID should be the same across all volumes, so just choose one
      VolumeManager fs;
      try {
        fs = VolumeManagerImpl.get();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      Path instanceIdPath = Accumulo.getAccumuloInstanceIdPath(fs);
      String deprecatedInstanceIdFromHdfs = ZooUtil.getInstanceIDFromHdfs(instanceIdPath, parent);
      instanceId = deprecatedInstanceIdFromHdfs;
    }
    return instance;
  }
  
  @Override
  public void invalidateCache() {
    if (propCache != null)
      propCache.clear();
  }
  
  private String _get(Property property) {
    String key = property.getKey();
    String value = null;
    
    if (Property.isValidZooPropertyKey(key)) {
      value = get(key);
    }
    
    if (value == null || !property.getType().isValidFormat(value)) {
      if (value != null)
        log.error("Using parent value for " + key + " due to improperly formatted " + property.getType() + ": " + value);
      value = parent.get(property);
    }
    return value;
  }
  
  @Override
  public String get(Property property) {
    if (Property.isFixedZooPropertyKey(property)) {
      if (fixedProps.containsKey(property.getKey())) {
        return fixedProps.get(property.getKey());
      } else {
        synchronized (fixedProps) {
          String val = _get(property);
          fixedProps.put(property.getKey(), val);
          return val;
        }
        
      }
    } else {
      return _get(property);
    }
  }
  
  private String get(String key) {
    String zPath = ZooUtil.getRoot(instanceId) + Constants.ZCONFIG + "/" + key;
    byte[] v = propCache.get(zPath);
    String value = null;
    if (v != null)
      value = new String(v, StandardCharsets.UTF_8);
    return value;
  }
  
  @Override
  public void getProperties(Map<String,String> props, PropertyFilter filter) {
    parent.getProperties(props, filter);

    List<String> children = propCache.getChildren(ZooUtil.getRoot(instanceId) + Constants.ZCONFIG);
    if (children != null) {
      for (String child : children) {
        if (child != null && filter.accept(child)) {
          String value = get(child);
          if (value != null)
            props.put(child, value);
        }
      }
    }
  }
}
