// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect

import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.impl.ClassLoadingUtils
import com.intellij.debugger.jdi.ObjectReferenceProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.jdi.VirtualMachineProxyImpl
import com.intellij.debugger.streams.inspect.service.RequesterStorageService
import com.intellij.debugger.streams.inspect.util.ClassLoaderHelper
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.sun.jdi.*
import com.sun.jdi.event.Event
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.EventRequestManager
import com.sun.jdi.request.MethodExitRequest
import org.apache.commons.io.IOUtils

class InspectingDebugListener : XDebugSessionListener {

  var peekArgsMethodValue : Value? = null


  inner class SwappingOnExitHandler : com.intellij.util.Consumer<Event> {
    override fun consume(e: Event?) {
      if (e == null) {
        return
      }

      if (e is MethodEntryEvent) {
        myEvaluator = xSession.debugProcess.evaluator?: return
        return
      }
      val exitEv = e as MethodExitEvent
      val method = exitEv.method() ?: return
      val returnValue = exitEv.returnValue() ?: return
      if (!returnValue.toString().startsWith("instance of")) {
        return
      }
      val newValue = createPeekCall(method, returnValue) ?: return
      threadProxy.forceEarlyReturn(newValue)
    }
  }

  private fun createPeekCall(method: Method, returnValue: Value): Value? {
    myEvaluator ?: return null
    val obj = returnValue as ObjectReferenceProxyImpl
    val peekMethod = DebuggerUtils.findMethod(obj.referenceType(), "peek", null)

    return obj.objectReference.invokeMethod(threadProxy.threadReference, peekMethod, listOf(peekArgsMethodValue!!), 0)
  }

  private val logger = logger<InspectingDebugListener>()
  private var exitRequest: MethodExitRequest? = null
  private lateinit var requestManager: EventRequestManager
  private lateinit var threadProxy: ThreadReferenceProxyImpl
  lateinit var xSession: XDebugSession
  var myEvaluator: XDebuggerEvaluator? = null

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



  private fun tryLoadClass(evalContextImpl: EvaluationContextImpl,
                           loadOption: ClassLoaderHelper.Companion.LoadClasses =
                             ClassLoaderHelper.Companion.LoadClasses.PeekInspector): ReferenceType? {
    val process = evalContextImpl.debugProcess as DebugProcessImpl

    val loadOptionPair = ClassLoaderHelper.loadOptions[loadOption]
    val className = loadOptionPair!!.first as String
    val classPath = loadOptionPair.second as String

    val bytes = IOUtils.toByteArray(
      this::class.java.classLoader.getResourceAsStream(classPath)
    )

    //evalContextImpl.isAutoLoadClasses = true
    val classLoader = ClassLoadingUtils.getClassLoader(evalContextImpl, process)


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


  private fun retrievePeekCallClassType(evalContextImpl: EvaluationContextImpl): ClassType? {

    val resultReference =  evalContextImpl.computeAndKeep<ClassObjectReference> {
      val start = System.currentTimeMillis()

      val ref = tryLoadClass(evalContextImpl)
      val end = System.currentTimeMillis() - start
      logger.info("Loading of peek inspector took $end ms")
      ref?.classObject()
    }


    return resultReference.reflectedType() as ClassType
  }


  override fun sessionPaused() {
    val service = service<RequesterStorageService>()
    val proj = service.project
    val xDebugProcess = xSession.debugProcess
    val process = DebuggerManager.getInstance(proj).getDebugProcess(xDebugProcess.processHandler)


    if (process != null) {
      val vm = process.virtualMachineProxy as VirtualMachineProxyImpl
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

      ctx = ctx.withAutoLoadClasses(true)

      //val peekInspectorClassType = retrievePeekCallClassType(ctx)?: return
      peekArgsMethodValue = setUpPeekArgumentValue(ctx)
      val a = 1
    }
  }

  private fun setUpPeekArgumentValue(evalContextImpl: EvaluationContextImpl, peekInspectorType: ClassType? = null): Value {
    val processImpl = evalContextImpl.debugProcess
    val threadRef = threadProxy.threadReference

    val innerConsumer = evalContextImpl.computeAndKeep{
      val ref = tryLoadClass(evalContextImpl, ClassLoaderHelper.Companion.LoadClasses.PeekInspectorConsumer)
      val classObj = ref?.classObject()!!
      classObj
      //var constructor = ref.methods().first {  it.name().contains("init") }
      //val instance = processImpl.newInstance(evalContextImpl, classObj.reflectedType() as ClassType , constructor, listOf() )
      //instance
    }


    val inspectorClassType = peekInspectorType ?: retrievePeekCallClassType(evalContextImpl)

    val neededMethodConsumer = inspectorClassType!!.methods().first {
      it.name().endsWith("getPeekConsumer")
    }

    val inspectorInstance = evalContextImpl.computeAndKeep {
      // first is default, last is param-based
      val constructor = inspectorClassType.methods().first() {
        it.name().contains("init")
      }

      //processImpl.newInstance(evalContextImpl, inspectorClassType, constructor, listOf(innerConsumer))
      processImpl.newInstance(evalContextImpl, inspectorClassType, constructor, listOf())
    }



    return  try {
      processImpl.invokeInstanceMethod(evalContextImpl, inspectorInstance, neededMethodConsumer, listOf(), 0)
    }
    catch (e: Exception) {
      throw e
    }
  }



  private fun evaluateArgument(): Value? {
    var ans : Value? = null

    val thisKlass = this.javaClass.classes

    val inspectingClass = thisKlass.first { it.name.startsWith("PeekMethodInspector") }
    val peekMethod = thisKlass[1].methods[0]


    myEvaluator!!.evaluate(XExpressionImpl.fromText("PeekMethodInspector::fluxInspector", EvaluationMode.CODE_FRAGMENT), object : XDebuggerEvaluator.XEvaluationCallback {
      override fun errorOccurred(errorMessage: String) {
        logger.debug(errorMessage)
      }

      override fun evaluated(result: XValue) {
        if (result is JavaValue) {
          val desc = result.descriptor
          ans = desc.value
        }
      }

    }, null)
    return ans
  }

}