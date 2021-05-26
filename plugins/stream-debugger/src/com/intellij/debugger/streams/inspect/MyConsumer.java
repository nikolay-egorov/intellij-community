// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

class MyConsumer implements Consumer<Object> {

  Long timeRef;
  HashMap<Integer, List<Object>> storage = new HashMap<>();

  MyConsumer(Long timeRef) {
    this.timeRef = timeRef;
  }

  int layerInd = 0;

  public HashMap<Integer, List<Object>> getStorage() {
    return storage;
  }

  public void incrementLayer() {
    layerInd++;
  }

  @Override
  public void accept(Object el) {
    if (!storage.containsKey(layerInd)) {
      storage.put(layerInd, new ArrayList<>());
    }
    storage.get(layerInd).add(el);
  }
}
