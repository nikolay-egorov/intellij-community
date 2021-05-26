// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect

import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.impl.ClassLoadingUtils
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.jdi.VirtualMachineProxyImpl
import com.intellij.debugger.streams.inspect.service.RequesterStorageService
import com.intellij.debugger.streams.inspect.util.ClassLoaderHelper
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.sun.jdi.*
import com.sun.jdi.event.Event
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.MethodExitRequest
import org.apache.commons.io.IOUtils
import org.jetbrains.annotations.NotNull

class InspectingDebugListener : XDebugSessionListener {

  var peekCallInspector : Value? = null
  var peekFluxConsumer : Value? = null
  val loadedClassMap = mutableMapOf<ClassLoaderHelper.Companion.LoadClasses, Boolean>()

  inner class SwappingOnExitHandler : com.intellij.util.Consumer<Event> {
    override fun consume(e: Event?) {
      if (e == null) {
        return
      }

      val exitEv = e as MethodExitEvent
      val method = exitEv.method() ?: return
      val returnValue = exitEv.returnValue() ?: return
      if (!returnValue.toString().startsWith("instance of")) {
        return
      }
      val type = returnValue.type()
      val newValue = createPeekCall(method, returnValue) ?: return
      threadProxy.forceEarlyReturn(newValue)
    }
  }

  private fun createPeekCall(method: Method, returnValue: Value): Value? {
    val obj = returnValue as ObjectReference
    val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "doOnNext", null)
    var shouldResume = false
    val ans = try {
      var ctx = evalContext!!
      val suspendManager = ctx.debugProcess.suspendManager
      shouldResume = ctx.suspendContext.isResumed
      //if (shouldResume) {
      //  evalContext!!.debugProcess.suspendManager.suspendThread(evalContext!!.suspendContext, threadProxy)
      //  //ctx.suspendContext.setIsEvaluating(ctx)
      //}

      //evalContext!!.debugProcess.invokeInstanceMethod(ctx, obj, peekMethod!!, listOf(peekFluxConsumer!!), 0)
      obj.invokeMethod(threadProxy.threadReference, peekMethod, listOf(peekFluxConsumer!!), 0)
    } catch (e : Exception) {
      throw e
    }

    if (shouldResume) {
      evalContext!!.debugProcess.suspendManager.resume(evalContext!!.suspendContext)
    }

    return ans
  }

  private val logger = logger<InspectingDebugListener>()
  private var exitRequest: MethodExitRequest? = null
  private lateinit var requestManager: EventRequestManager
  private lateinit var threadProxy: ThreadReferenceProxyImpl
  lateinit var xSession: XDebugSession
  var myEvaluator: XDebuggerEvaluator? = null
  var evalContext: EvaluationContextImpl? = null


  private fun createExitRequest(): MethodExitRequest {
    DebuggerManagerThreadImpl.assertIsManagerThread() // to ensure EventRequestManager synchronization
    if (exitRequest != null) {
      requestManager.deleteEventRequest(exitRequest)
    }
    val methodExitRequest = requestManager.createMethodExitRequest()
    methodExitRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
    methodExitRequest.addThreadFilter(threadProxy.threadReference) // todo: perhaps remove
    methodExitRequest.addClassFilter("reactor.core.publisher.Flux*")
    methodExitRequest.enable()
    exitRequest = methodExitRequest
    return exitRequest!!
  }


  override fun sessionPaused() {
    val service = service<RequesterStorageService>()
    val proj = service.project
    val xDebugProcess = xSession.debugProcess
    val process = DebuggerManager.getInstance(proj).getDebugProcess(xDebugProcess.processHandler)


    if (process != null) {
      val vm = process.virtualMachineProxy as VirtualMachineProxyImpl
      if (peekCallInspector != null) {
        return
      }
      requestManager = vm.eventRequestManager()
      val processImpl = process as DebugProcessImpl
      val context = processImpl.suspendManager.pausedContext

      val threadRef = context.thread!!.threadReference
      threadProxy = vm.getThreadReferenceProxy(threadRef)!!

      val eval = xDebugProcess.evaluator
      if (eval != null) {
        myEvaluator = eval
      }


      DebugProcessEvents.enableRequestWithHandler(createExitRequest(), SwappingOnExitHandler())

      var ctx = if (context.evaluationContext != null) {
        context.evaluationContext
      } else {
        processImpl.debuggerContext.createEvaluationContext()!!
      }

      if (peekCallInspector == null) {
        ctx = ctx.withAutoLoadClasses(true)

        val classLoader = ClassLoadingUtils.getClassLoader(ctx, ctx.debugProcess)
        loadAdditionalClasses(ctx, classLoader)
        peekCallInspector = setUpPeekInspector(ctx, classLoader)
        evalContext = ctx
        peekFluxConsumer = setUpPeekConsumer(ctx, classLoader, peekCallInspector as ObjectReference)
      }

      val a = 1
    }
  }

  // for load
  private fun loadAdditionalClasses(evalContextImpl: EvaluationContextImpl, classLoader: ClassLoaderReference) {
    val toLoadKeys = ClassLoaderHelper.getAdditionalClasses()
    toLoadKeys.forEach { tryLoadClass(evalContextImpl, classLoader, it) }
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

    if (loadOption != ClassLoaderHelper.Companion.LoadClasses.JavaConsumer && !loadedClassMap.contains(loadOption)) {
      try {
        ClassLoadingUtils.defineClass(className, bytes, evalContextImpl, process, classLoader)
      } catch (e: EvaluateException) {
        throw EvaluateExceptionUtil.createEvaluateException(e.message, e)
      }
    }
    loadedClassMap.putIfAbsent(loadOption, true)

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


  private fun retrievePeekCallClassType(evalContextImpl: EvaluationContextImpl, classLoader: ClassLoaderReference): ClassType? {

    val resultReference =  evalContextImpl.computeAndKeep<ClassObjectReference> {
      val start = System.currentTimeMillis()

      val ref = tryLoadClass(evalContextImpl, classLoader)
      val end = System.currentTimeMillis() - start
      logger.info("Loading of peek inspector took $end ms")
      ref?.classObject()
    }

    return resultReference.reflectedType() as ClassType
  }


  private fun setUpPeekInspector(evalContextImpl: EvaluationContextImpl,
                                classLoader: ClassLoaderReference,
                                peekInspectorType: ClassType? = null): Value {
    val processImpl = evalContextImpl.debugProcess
    val inspectorClassType = peekInspectorType ?: retrievePeekCallClassType(evalContextImpl, classLoader)

    return evalContextImpl.computeAndKeep {
      // first is default, last is param-based
      val constructor = inspectorClassType!!.methods().first() {
        it.name().contains("init")
      }

      processImpl.newInstance(evalContextImpl, inspectorClassType, constructor, listOf())
    }
  }

  private fun setUpPeekConsumer(evalContextImpl: EvaluationContextImpl, classLoader: ClassLoaderReference, peekInspectorInstance: ObjectReference? = null): Value {
    val processImpl = evalContextImpl.debugProcess
    val threadRef = threadProxy.threadReference

    val inspectorClassType = retrievePeekCallClassType(evalContextImpl, classLoader)

    val neededMethodConsumer = inspectorClassType!!.methods().first {
      it.name().endsWith("getPeekConsumer")
    }

    val inspectorInstance = peekInspectorInstance ?: setUpPeekInspector(evalContextImpl, classLoader, inspectorClassType)

    return  try {
      processImpl.invokeInstanceMethod(evalContextImpl, inspectorInstance as @NotNull ObjectReference, neededMethodConsumer, listOf(), 0)
    }
    catch (e: Exception) {
      throw e
    }
  }


}