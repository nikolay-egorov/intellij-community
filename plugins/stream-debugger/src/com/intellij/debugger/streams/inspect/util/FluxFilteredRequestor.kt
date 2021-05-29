// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.util

import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.impl.DebuggerManagerImpl
import com.intellij.debugger.ui.breakpoints.FilteredRequestor
import com.intellij.debugger.ui.breakpoints.FilteredRequestorImpl
import com.intellij.openapi.project.Project
import com.intellij.psi.filters.ClassFilter
import com.intellij.xdebugger.XDebugSession
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.Value
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.event.MethodExitEvent

class FluxFilteredRequestor(project: Project, val xSession: XDebugSession) : FilteredRequestorImpl(project) {
  var peekFluxConsumer: Value? = null

  fun setFluxConsumer(value: Value) {
    peekFluxConsumer = value
  }

  fun createPeekCall(method: Method, returnValue: Value) : Value? {
    val obj = returnValue as ObjectReference
    //val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "doOnNext", null)
    val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "count", null)
    val ans = try {
      val manager = DebuggerManager.getInstance(myProject) as DebuggerManagerImpl
      val ctxManager = manager.contextManager
      val process = manager.getDebugProcess(xSession.debugProcess.processHandler)
      val processImpl = process as DebugProcessImpl
      val ctxx = processImpl.debuggerContext.suspendContext ?: return null
      val evalCtx = ctxx.evaluationContext

      processImpl.invokeInstanceMethod(evalCtx, returnValue, peekMethod!!, listOf(), 0 )
    } catch (e : Exception) {
      throw e
    }

    return ans
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
    val newReturnVal = createPeekCall(method, returnValue) ?: return false
    thread.forceEarlyReturn(newReturnVal)
    return true

    //return super.processLocatableEvent(action, e)
  }
}