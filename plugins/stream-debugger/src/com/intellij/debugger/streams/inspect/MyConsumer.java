// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect;

import reactor.core.publisher.Flux;

import java.util.function.Consumer;

class MyConsumer implements Consumer<Flux<?>> {
  int storage;

  @Override
  public void accept(Flux<?> flux) {

  }
}
