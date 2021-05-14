// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect;

import java.util.function.Consumer;

public class PeekCallInspector {

  public MyConsumer myConsumer; // = new MyConsumer();


  public PeekCallInspector() {

  }

  public PeekCallInspector(MyConsumer consumer) {
    myConsumer = consumer;
  }


  public Consumer<Integer> getPeekConsumer() {
    return new MyConsumer();
  }
}
