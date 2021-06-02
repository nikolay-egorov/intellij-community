// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.service

import com.intellij.debugger.engine.jdi.LocalVariableProxy
import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.debugger.streams.wrapper.StreamCall
import com.intellij.debugger.streams.wrapper.impl.StreamCallImpl
import com.intellij.debugger.streams.wrapper.impl.StreamChainImpl
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.containers.Stack
import com.intellij.util.containers.enumMapOf
import com.sun.jdi.*
import java.util.*

typealias variablesMap = Map<LocalVariable, Value>

@Service   // private val project: Project
class DynamicInterpretationService() {

  companion object {
    val setOfNeededBreakPoints = mutableSetOf<String>("onAssembly", "onLastAssembly")
  }

  lateinit var startingThread: ThreadReference
  var stackCall: Stack<StackFrame> = Stack()
  var resolvedStorage: MutableMap<StreamCallImpl, MutableMap<StackFrame, variablesMap>> = LinkedHashMap()

  fun storeVariables(streamCallImpl: StreamCallImpl ,stackFrameProxy: StackFrameProxy, localVars: variablesMap) {
    val method = stackFrameProxy.location().method()
    val stackFrame = stackFrameProxy.stackFrame
    if (!resolvedStorage.containsKey(streamCallImpl)) {
      resolvedStorage.put(streamCallImpl, HashMap())
    }
    resolvedStorage[streamCallImpl]!!.put(stackFrame, localVars)
  }
}

