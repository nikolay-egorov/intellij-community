// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.util

import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.impl.DebuggerManagerImpl
import com.intellij.debugger.settings.DebuggerSettings.SUSPEND_THREAD
import com.intellij.debugger.ui.breakpoints.FilteredRequestorImpl
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.event.MethodExitEvent

class FluxFilteredRequestor(project: Project, val xSession: XDebugSession) : FilteredRequestorImpl(project) {
  var peekFluxConsumer: Value? = null
  var evaluatedOnce = false

  init {
    SUSPEND_POLICY = SUSPEND_THREAD
    CLASS_FILTERS_ENABLED = true
  }


  fun setFluxConsumer(value: Value) {
    peekFluxConsumer = value
  }

  fun createPeekCall(threadReference: ThreadReference, action: SuspendContextCommandImpl, method: Method, returnValue: Value): Value? {
    val obj = returnValue as ObjectReference
    val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "doOnNext", null) ?: return null
    //val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "count", null) ?: return null

    return try {
      val ctx = action.suspendContext ?: return null
      //val processImpl = ctx.debugProcess
      val manager = DebuggerManager.getInstance(myProject) as DebuggerManagerImpl
      val process = manager.getDebugProcess(xSession.debugProcess.processHandler)
      val processImpl = process as DebugProcessImpl

      var evalCtx = ctx.evaluationContext
      if (evalCtx == null) {
        val frameProxy = ctx.frameProxy!!
        val evlCtx = EvaluationContextImpl(ctx, frameProxy)
        evalCtx = evlCtx
      }

      // works fine with count for some reason
      processImpl.invokeInstanceMethod(evalCtx, returnValue, peekMethod, listOf(peekFluxConsumer!!), 0)
      // still infinite wait
      //returnValue.invokeMethod(threadReference, peekMethod, listOf(), ObjectReference.INVOKE_SINGLE_THREADED)
    }
    catch (e: Exception) {
      throw e
    }
  }

  override fun processLocatableEvent(action: SuspendContextCommandImpl, e: LocatableEvent?): Boolean {
    if (e == null) {
      return false
    }

    val exitEv = e as MethodExitEvent
    val method = exitEv.method() ?: return false
    val returnValue = exitEv.returnValue() ?: return false
    if (!returnValue.toString().startsWith("instance of")) {
      return false
    }

    val thread = e.thread()
    if (evaluatedOnce) {
      evaluatedOnce = false
      return false
    }
    val newReturnVal = createPeekCall(thread, action, method, returnValue) ?: return false
    evaluatedOnce = true
    thread.forceEarlyReturn(newReturnVal)
    
    return true

    //return super.processLocatableEvent(action, e)
  }
}