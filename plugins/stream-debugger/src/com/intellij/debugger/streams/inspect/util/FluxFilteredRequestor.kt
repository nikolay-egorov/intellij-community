// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.util

import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.impl.ClassLoadingUtils
import com.intellij.debugger.impl.DebuggerManagerImpl
import com.intellij.debugger.settings.DebuggerSettings.SUSPEND_THREAD
import com.intellij.debugger.ui.breakpoints.FilteredRequestorImpl
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.sun.jdi.*
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.event.MethodExitEvent
import org.apache.commons.io.IOUtils

class FluxFilteredRequestor(project: Project, val xSession: XDebugSession) : FilteredRequestorImpl(project) {
  var peekFluxConsumer: Value? = null
  var evaluatedOnce = false
  var wasLoaded = false
  init {
    SUSPEND_POLICY = SUSPEND_THREAD
    CLASS_FILTERS_ENABLED = true
  }


  fun setFluxConsumer(value: Value) {
    peekFluxConsumer = value
  }



  private fun tryLoadClass(evalContextImpl: EvaluationContextImpl, classLoader: ClassLoaderReference,
                           loadOption: ClassLoaderHelper.Companion.LoadClasses =
                             ClassLoaderHelper.Companion.LoadClasses.PeekInspector): ReferenceType? {
    val process = evalContextImpl.debugProcess as DebugProcessImpl

    val loadOptionPair = ClassLoaderHelper.loadOptions[loadOption]
    val className = loadOptionPair!!.first as String
    val classPath = loadOptionPair.second as String

    val bytes = IOUtils.toByteArray(
      this::class.java.classLoader.getResourceAsStream(classPath)
    )

    if (loadOption != ClassLoaderHelper.Companion.LoadClasses.JavaConsumer) {
      try {
        ClassLoadingUtils.defineClass(className, bytes, evalContextImpl, process, classLoader)
      } catch (e: EvaluateException) {
        throw EvaluateExceptionUtil.createEvaluateException(e.message, e)
      }
    }

    return try {
      process.loadClass(evalContextImpl, className, classLoader)
    }
    catch (e: Exception) {
      when (e) {
        is InvocationException, is ClassNotLoadedException, is IncompatibleThreadStateException, is InvalidTypeException -> {
          throw EvaluateExceptionUtil.createEvaluateException("Could not load class", e)
        }
        else -> throw e
      }
    }
  }





  fun createPeekCall(threadReference: ThreadReference, action: SuspendContextCommandImpl, method: Method, returnValue: Value): Value? {
    val obj = returnValue as ObjectReference
    val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "doOnNext", null) ?: return null
    //val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "count", null) ?: return null

    val ans = try {
      val ctx = action.suspendContext ?: return null
      val manager = DebuggerManager.getInstance(myProject) as DebuggerManagerImpl
      val process = manager.getDebugProcess(xSession.debugProcess.processHandler)
      //val processImpl = process as DebugProcessImpl
      val processImpl = ctx.debugProcess

      var evalCtx = ctx.evaluationContext
      if (evalCtx == null) {
        val frameProxy = ctx.frameProxy!!
        val evlCtx = EvaluationContextImpl(ctx, frameProxy)
        evalCtx = evlCtx
        //val loader = ClassLoadingUtils.getClassLoader(evalCtx, evalCtx.debugProcess)
        //evalCtx.debugProcess.suspendManager.voteSuspend(ctx)
        //if (!wasLoaded) {
        //  tryLoadClass(evalCtx, loader, ClassLoaderHelper.Companion.LoadClasses.PeekInspectorConsumer)
        //  wasLoaded = true
        //}
      }
      evalCtx.computeAndKeep {
        peekFluxConsumer
      }
      val suspendManager = processImpl.suspendManager

      val res = processImpl.invokeInstanceMethod(evalCtx, returnValue, peekMethod, listOf(peekFluxConsumer!!), 0, false)
      //val res = processImpl.invokeInstanceMethod(evalCtx, returnValue, peekMethod, listOf(), 0, true)
      res
      // still infinite wait
      //returnValue.invokeMethod(threadReference, peekMethod, listOf(), ObjectReference.INVOKE_SINGLE_THREADED)
    }
    catch (e: Exception) {
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
    if (evaluatedOnce) {
      evaluatedOnce = false
      return false
    }
    val newReturnVal = createPeekCall(thread, action, method, returnValue) ?: return false
    evaluatedOnce = true
    thread.forceEarlyReturn(newReturnVal)

    return true
  }
}