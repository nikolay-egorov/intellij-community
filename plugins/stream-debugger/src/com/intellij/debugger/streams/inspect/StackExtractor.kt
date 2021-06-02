// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.managerThread.DebuggerCommand
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.streams.inspect.represent.VarInfo
import com.intellij.debugger.streams.inspect.storage.DebugStorage
import com.intellij.openapi.util.Key
import com.sun.jdi.*
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import java.util.stream.Collectors


class StackExtractor(private val stackFrameProxy: StackFrameProxyImpl, private val suspendContext: SuspendContext,
                     private val debugProcess: DebugProcessImpl = (suspendContext.debugProcess as DebugProcessImpl?)!!) : DebuggerCommand {


  companion object {
    private val debugStorage = DebugStorage.instance
  }


  override fun action() {
    extractLocalVariables()
    resumeIfOnlyRequestor()
  }

  override fun commandCancelled() {

  }

  private fun resumeIfOnlyRequestor() {
    val suspendManager: SuspendManager = debugProcess.suspendManager
    var isOnlyEventRequest = true

    // this loop detects:
    // -breakpoint requests
    outer@ for (context: SuspendContextImpl in suspendManager.eventContexts) {
      val events = context.eventSet
      val eventIterator = events!!.eventIterator()
      while (eventIterator.hasNext()) {
        val e = eventIterator.nextEvent()
        val request: EventRequest = e.request()
        if (request is BreakpointRequest) {
          val o = request.getProperty(Key.findKeyByName("Requestor"))
          if (o != null && o is EvaluationListener) {
            continue
          }
        }
        isOnlyEventRequest = false
        break@outer
      }
    }

    if (isOnlyEventRequest) suspendManager.resume(suspendContext as SuspendContextImpl)
  }


  private fun extractLocalVariables() {
    try {
      val frame = stackFrameProxy.stackFrame
      val localVariables = frame.visibleVariables()
      val map = frame.getValues(localVariables)
      //updateCache(map)
    }
    catch (e: AbsentInformationException) {
      e.printStackTrace()
    }
    catch (e: EvaluateException) {
      e.printStackTrace()
    }
  }



  private fun getObjectId(): String? {
    try {
      val frame: StackFrame = stackFrameProxy.stackFrame
      val thisObject = frame.thisObject()
      return if (thisObject != null) {
        thisObject.referenceType().name() + "(id=" + thisObject.uniqueID() + ")"
      }
      else {
        // if the frame is in a native or static method
        val referenceType: ReferenceType = stackFrameProxy.location().declaringType()
        referenceType.name()
      }
    }
    catch (e: EvaluateException) {
      e.printStackTrace()
    }
    return null
  }


  private fun valueAsString(value: Value?): String {
    var valueAsString = "null"
    if (value != null) {
      valueAsString = value.toString()
      when (value) {
        is StringReference -> {
          valueAsString = (value as StringReference).value()
        }
        is ArrayReference -> {
           valueAsString = getValueFromArrayReference(value as ArrayReference)
        }
      }
      //else if (value is ObjectReference) {
      //  valueAsString = invokeToString(value as ObjectReference?)
      //}
    }
    return valueAsString
  }


  private fun getValueFromArrayReference(value: ArrayReference): String {
    var valueAsString = "["
    val arrayValues: List<String> = value.values
      .stream()
      .map { v: Value? -> valueAsString(v) }
      .collect(Collectors.toList())
    valueAsString += java.lang.String.join(", ", arrayValues)
    valueAsString += "]"
    return valueAsString
  }


}