// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.util

class ClassLoaderHelper {
  companion object {
     enum class LoadClasses {
       PeekInspector,
       PeekInspectorConsumer,
       RequestorStorage,
       JavaConsumer
    }

    const val peekClassName = "com.intellij.debugger.streams.inspect.PeekCallInspector"
    const val peekClassPath = "com/intellij/debugger/streams/inspect/PeekCallInspector.class"
    const val peekInnerConsumerClassPath = "com/intellij/debugger/streams/inspect/MyConsumer.class"
    const val peekInnerConsumerClassName = "com.intellij.debugger.streams.inspect.MyConsumer"
    const val requestorStorageName = "com.intellij.debugger.streams.inspect.service.RequesterStorageService"
    const val requestorStoragePath = "com/intellij/debugger/streams/inspect/service/RequesterStorageService.class"
    const val javaConsumerName = "java.util.function.Consumer"
    const val javaConsumerPath = "java/util/function/Consumer.class"
    const val fluxClassName = "reactor.core.publisher.Flux"
    const val fluxClassPath = "reactor/core/publisher/Flux.class"

    val loadOptions = mapOf<LoadClasses, Pair<String, String>>(
      LoadClasses.PeekInspector to (peekClassName to peekClassPath),
      LoadClasses.PeekInspectorConsumer to (peekInnerConsumerClassName to peekInnerConsumerClassPath),
      LoadClasses.RequestorStorage to (requestorStorageName to requestorStoragePath),
      LoadClasses.JavaConsumer to (javaConsumerName to javaConsumerPath),
    ).withDefault {
      LoadClasses.PeekInspector to (peekClassName to peekClassPath)
    }

    fun getAdditionalClasses() : List<LoadClasses> {
      return listOf(LoadClasses.PeekInspectorConsumer, LoadClasses.RequestorStorage)
    }
  }

}