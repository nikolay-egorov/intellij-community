// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.service

import com.intellij.debugger.streams.inspect.storage.StreamStorage
import com.intellij.debugger.streams.wrapper.StreamChain
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import reactor.core.publisher.Flux

@Service
class RequesterStorageService {

  lateinit  var project: Project
  var currentSession: XDebugSession? = null
  var resolvedStorage: MutableMap<String, StreamStorage> = LinkedHashMap()
  var totalStreams = 0
  var currentStream = 0

  val testFile = "ReactorCreating.java"

  fun addNewChain(file: VirtualFile, streamChain: StreamChain) {
    var fqn = "${file.name}${streamChain.qualifierExpression.text}"
    if (currentStream == 0) {
      fqn = "${file.name}${currentStream}"
    }
    if (resolvedStorage.containsKey(fqn)) {
      return
    }

    resolvedStorage[fqn] = StreamStorage(fqn, streamChain)
    currentStream++

  }

  fun reset() {
    resolvedStorage.clear()
    totalStreams = 0
    currentStream = 0
  }

  fun addToTestStorage(flux: Flux<Any>, isLast : Boolean = false) {
    val fqn = "${testFile}${0}"
    val storage = resolvedStorage[fqn]?: return
    val layer = storage.currentLayer
    storage.addToLayer(layer, flux)
  }

  fun addToChain(streamChain: StreamChain, layerNum : Int, flux: Flux<Any>) {
    val storage = resolvedStorage[streamChain]
    storage?.addToLayer(layerNum, flux)
  }

}