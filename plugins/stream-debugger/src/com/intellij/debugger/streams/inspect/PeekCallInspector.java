// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect;

import java.util.function.Consumer;

public class PeekCallInspector {

  int currentID;
  Long currTime = Long.valueOf(0);

  public MyConsumer myConsumer;

  public PeekCallInspector() {
    myConsumer = new MyConsumer(currTime);
  }

  public PeekCallInspector(MyConsumer consumer) {
    myConsumer = consumer;
  }


  public Consumer<?> getPeekConsumer() {
    return myConsumer;
  }

  public void increaseTime() {
    currTime++;
  }
}
