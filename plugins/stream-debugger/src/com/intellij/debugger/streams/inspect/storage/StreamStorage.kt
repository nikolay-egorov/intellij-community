// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.storage

import com.intellij.debugger.streams.trace.TraceInfo
import com.intellij.debugger.streams.wrapper.StreamCall
import com.intellij.debugger.streams.wrapper.StreamChain
import reactor.core.publisher.Flux

data class CustomTraceElement(val time: Int = 1, val element: Flux<Any>)


class LayerInfo {

  private var layerTime: Int = 0
  //private val myValuesOrderAfter: MutableMap<Int, TraceElement> = HashMap()
  //private val myValuesOrderBefore: MutableMap<Int, TraceElement> = HashMap()

  private val myValuesOrderAfter: MutableMap<Int, CustomTraceElement> = HashMap()
  private val myValuesOrderBefore: MutableMap<Int, CustomTraceElement> = HashMap()

  fun addToLayer(number: Int, value: Flux<Any>, isBefore: Boolean = true) {
    if (isBefore) {
      myValuesOrderBefore[number] = CustomTraceElement(layerTime++, value)
    } else {
      myValuesOrderAfter[number] = CustomTraceElement(layerTime++, value)
    }
  }

}


class StreamStorage(private val streamID: String, private val streamChain: StreamChain) {
  private val storage: MutableMap<StreamCall, TraceInfo> = LinkedHashMap()
  private val preComputeStorage: MutableMap<StreamCall, LayerInfo> = LinkedHashMap()

  var currentLayer = 0
  var inLayerNumber = 0

  fun addToLayer(number: Int, flux: Flux<Any>) {
    var call = streamChain.getCall(number)

    if (number != currentLayer) {
      currentLayer = number
      inLayerNumber = 0
    }

    if (!preComputeStorage.containsKey(call)) {
      preComputeStorage[call] = LayerInfo()
    }
    val info = preComputeStorage[call]
    info!!.addToLayer(inLayerNumber, flux, true)


  //  if (call != streamChain.terminationCall) {
  //    call = call as IntermediateStreamCall
  //    val typeBefore = call.typeBefore
  //    val typeAfter = call.typeAfter
  //    if (!preComputeStorage.containsKey(call)) {
  //      preComputeStorage[call] = LayerInfo()
  //    }
  //    val info = preComputeStorage[call]
  //    info!!.addToLayer(inLayerNumber, flux, true)
  //  }

  }
}