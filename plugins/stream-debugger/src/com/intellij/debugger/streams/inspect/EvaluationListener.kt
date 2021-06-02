// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.evaluation.EvaluationListener
import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.impl.DebuggerContextListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.jdi.LocalVariablesUtil
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.streams.inspect.service.DynamicInterpretationService
import com.intellij.debugger.streams.inspect.service.DynamicInterpretationService.Companion.setOfNeededBreakPoints
import com.intellij.debugger.streams.inspect.storage.DebugStorage
import com.intellij.debugger.streams.lib.impl.reactive.ReactorSupportProvider
import com.intellij.debugger.streams.wrapper.StreamCall
import com.intellij.debugger.streams.wrapper.StreamChain
import com.intellij.debugger.streams.wrapper.impl.StreamCallImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.LOG
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.containers.filterSmart
import com.intellij.xdebugger.frame.*
import com.sun.jdi.*
import com.sun.jdi.event.BreakpointEvent
import java.util.*
import javax.swing.Icon


class EvaluationListener(private val excludedLib: List<String>) : EvaluationListener {


  inner class StreamChainStateSyncer {

    lateinit var streamChain: StreamChain

    var currentPos: Int = -1

    private var layerSize = LongArray(3)

    private var layerElements: MutableMap<Int, List<Any>> = HashMap()

    var currentStreamCall: StreamCall? = null

    val peekOperationCallName = "doOnSignal"

    var isBetweenLayers = false

    fun getStreamCallorCurrent(ind: Int = currentPos): StreamCall?  {
      if (ind == -1) {
        return null
      }
      if (ind >= streamChain.length()) {
        return streamChain.terminationCall
      }
      return streamChain.getCall(ind)
    }


    fun updateStream(chain: StreamChain) {
      currentPos = -1
      layerElements.clear()
      streamChain = chain
      layerSize = LongArray(chain.length()){ 0 }
      isBetweenLayers = false
    }

    fun updateCurrentLayerSize(size: Long) {
      layerSize[currentPos] = size
    }

    fun incrementIntermediateSize() {
      if (layerSize.isEmpty()) {
        layerSize = LongArray(streamChain.length()) { 0 }
      }
      layerSize[currentPos]++
    }



  }
  // DebugProcessListener - ok / DebugProcessAdapterImpl() ?
  inner class BreakPointListener : DebuggerContextListener, DebugProcessListener { // , XDebugSessionListener, ClassPrepareRequestor {

    var evalStarted: Boolean = false
    lateinit var debugEvalProcess: DebugProcess

    var storedVars: MutableMap<String, XValue> = HashMap()

    private fun retrieveValuesFromFrame(currentStackFrame: XStackFrame) {
      val compositeNode = object : XCompositeNode {
        override fun addChildren(children: XValueChildrenList, last: Boolean) {
          for (i in 0..children.size()) {
            val childName = children.getName(i)
            val childValue = children.getValue(i)
            childValue.instanceEvaluator
          }

        }

        override fun tooManyChildren(remaining: Int) {

        }

        override fun setAlreadySorted(alreadySorted: Boolean) {

        }

        override fun setErrorMessage(errorMessage: String) {

        }

        override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {

        }

        override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {

        }
      }

      currentStackFrame.computeChildren(compositeNode)
    }


    // DebuggerContextListener
    // seems to be same as detached()
    override fun changeEvent(newContext: DebuggerContextImpl, event: DebuggerSession.Event?) {
      if (event == DebuggerSession.Event.DETACHED) {
        DebugStorage.instance.clear()
      }
    }


    fun checkSameThread(threadReference: ThreadReference, stackFrameProxy: StackFrameProxy): Boolean {
        return threadReference == stackFrameProxy.threadProxy().threadReference
    }

    // works ok
    //override fun sessionPaused() {
    //  super.sessionPaused()
    //}

    // todo: maybe add somehow breapoints?
    //override fun processClassPrepare(debuggerProcess: DebugProcess, referenceType: ReferenceType) {
    //  registerBreakPointRequests(debuggerProcess, referenceType)
    //}
    //
    //private fun registerBreakPointRequests(debuggerProcess: DebugProcess, referenceType: ReferenceType) {
    //  val executableLines: List<Location>
    //  executableLines = try {
    //    referenceType.allLineLocations()
    //  }
    //  catch (exc: AbsentInformationException) {
    //    exc.printStackTrace()
    //    return
    //  }
    //
    //  val requestManager = debuggerProcess.requestsManager as RequestManagerImpl
    //  for (loc in executableLines) {
    //    if (!loc.method().name().contains("toString")) { // drop all reactive here
    //      val req = requestManager.createBreakpointRequest(this, loc)
    //      req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
    //      req.enable()
    //    }
    //  }
    //}

    override fun paused(suspendContext: SuspendContext) {
      if (suspendContext is SuspendContextImpl) {
        val frameProxy = suspendContext.frameProxy
        val eventSet = suspendContext.eventSet ?: return
        val event = eventSet.eventIterator().nextEvent()
        if (event is BreakpointEvent) {
          if (event.location().declaringType().name().contains(ReactorSupportProvider.CLASS_QN_PREFIX)) {
            if (suspendContext.debugProcess.xdebugProcess != null) { // java-specific
              val evaluator = suspendContext.debugProcess.xdebugProcess!!
              val frame = suspendContext.frameProxy!!.stackFrame
              val visibleVariables = frame.visibleVariables()
              val valuesMap = frame.getValues(visibleVariables)
              val location = frame.location()
              tryLinkOnAssemblyMethod(suspendContext.frameProxy!!)


              //val service = service<DynamicInterpretationService>()
              //service.storeVariables(suspendContext.frameProxy!!, valuesMap)

              //if (frame != null) {
              //  retrieveValuesFromFrame(frame)
              //}
            }
          }
        }
      }
      super.paused(suspendContext)
    }

    private fun tryLinkOnAssemblyMethod(stackFrameProxy: StackFrameProxyImpl) {
      val stackFrame = stackFrameProxy.stackFrame
      val currentLocation = stackFrame.location()
      if (currentLocation.method().name() !in setOfNeededBreakPoints) {
        return
      }
      val threadProxy = stackFrameProxy.threadProxy()
      threadProxy.virtualMachine.eventRequestManager().methodExitRequests().first().enable()
      val currentStreamCall = streamChainStateSyncer.getStreamCallorCurrent()

      // very first call
      if (currentStreamCall == null) {
        val currentStackFrame = stackFrameProxy
        //retrieveValuesFromFrame(stackFrameProxy.)
        val visibleVariables = stackFrame.visibleVariables()
        val valuesMap = stackFrame.getValues(visibleVariables)
        val sourceVar = visibleVariables.filterSmart { it.name() == "source" }[0]
        val sourceValue = valuesMap[sourceVar]
        if (sourceValue is ObjectReference) {
          val referenceType = sourceValue.referenceType()
          val instances = referenceType.instances(2)
          val a = 1
        }
        streamChainStateSyncer.currentPos++
        streamChainStateSyncer.currentStreamCall = streamChainStateSyncer.getStreamCallorCurrent()
        streamChainStateSyncer.isBetweenLayers = true
        return
      }

      // intermidiate calls would be doOnNext -> $.invoke()
      var isBetween = streamChainStateSyncer.currentPos == 0 || streamChainStateSyncer.isBetweenLayers

      var methodName = if (isBetween) {
        streamChainStateSyncer.peekOperationCallName
      } else {
        currentStreamCall.name
      }
      var framePosition = stackFrameProxy.frameIndex
      var previousStackFrame = threadProxy.frame(framePosition++)
      var nextStackFrame = threadProxy.frame(framePosition)
      val currentStreamOp = currentStreamCall.name


      while (nextStackFrame.location().method().name() != methodName && framePosition + 1 <= threadProxy.frameCount()) {
        if (nextStackFrame.location().method().name() == currentStreamOp) {
          isBetween = false
          methodName = currentStreamOp
          break
        }

        previousStackFrame = nextStackFrame
        framePosition++
        nextStackFrame = threadProxy.frame(framePosition)
      }

      if (nextStackFrame.location().method().name() != methodName) {
        return
      }
      // means we are in between layers
      if (isBetween || methodName != streamChainStateSyncer.currentStreamCall?.name) {
        streamChainStateSyncer.incrementIntermediateSize()
        gatherData(currentStreamCall as StreamCallImpl, previousStackFrame, nextStackFrame)
      } else {
        streamChainStateSyncer.currentPos++
        streamChainStateSyncer.currentStreamCall = streamChainStateSyncer.getStreamCallorCurrent()
        streamChainStateSyncer.isBetweenLayers = true
      }
      return
      //if (isFirst) {
      //  gatherData(previousStackFrame, nextStackFrame)
      //  streamChainStateSyncer.currentPos++
      //  return
      //}
      //
      //if (streamChainStateSyncer.currentStreamCall != null) {
      //  if (streamChainStateSyncer.currentStreamCall == currentStreamCall) {
      //    streamChainStateSyncer.incrementIntermediateSize()
      //  } else {
      //    streamChainStateSyncer.currentStreamCall = currentStreamCall
      //    streamChainStateSyncer.currentPos++
      //  }
      //}
      // traceSeveralTimes

    }


    private fun gatherData(streamCallImpl: StreamCallImpl, stackFrameToGather: StackFrameProxyImpl, stackFrameNext: StackFrameProxyImpl?) {
      val stackFrame = stackFrameToGather.stackFrame
      val visibleVariables = stackFrame.visibleVariables()
      val valuesMap = stackFrame.getValues(visibleVariables)
      val service = service<DynamicInterpretationService>()

      if (stackFrameNext != null) {
        service.storeVariables(streamCallImpl, stackFrameNext, valuesMap)
      } else {
        service.storeVariables(streamCallImpl, stackFrameToGather, valuesMap)
      }
    }


    /*
      guess: happens after eval is stopped - nope
      happens after debugger finished steps
     */
    //
    override fun processDetached(process: DebugProcess, closedByUser: Boolean) {
      super.processDetached(process, closedByUser)
    }


    // FilteredRequestor
    //override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent?): Boolean {
    //  if (event != null && event is BreakpointEvent) {
    //    val breakpointEvent = event
    //    val suspendContext = action.suspendContext
    //    val sfProxy = action.suspendContext!!.frameProxy!!
    //    val fetchedValues = LocalVariablesUtil.fetchValues(sfProxy, suspendContext!!.debugProcess, true)
    //    if (suspendContext != null && event.thread().isAtBreakpoint) {
    //      val extractor = StackExtractor(sfProxy, suspendContext)
    //      val managerThread = suspendContext.debugProcess
    //        .managerThread
    //      managerThread.invokeCommand(extractor)
    //
    //    }
    //  }
    //  return true
    //}

  }

  private val breakPointListener = BreakPointListener()

  private lateinit var lastSubscribedProcess: DebugProcessImpl

  private val streamChainStateSyncer = StreamChainStateSyncer()


  fun setChain(chain: StreamChain) {
    streamChainStateSyncer.updateStream(chain)
  }

  // EvaluationListener
  override fun evaluationStarted(context: SuspendContextImpl) {

    if (!breakPointListener.evalStarted) {
      breakPointListener.evalStarted = true
      val activeExecutionStack = context.activeExecutionStack
      val session = context.debugProcess.session
      lastSubscribedProcess = session.process
      //session.process.xdebugProcess!!.session.addSessionListener(breakPointListener)
      session.process.addDebugProcessListener(breakPointListener)
      breakPointListener.debugEvalProcess = session.process
      //session.contextManager.addListener(breakPointListener) - seems not to work fine
    }

  }


  override fun evaluationFinished(context: SuspendContextImpl) {
    val session = context.debugProcess.session
    // see LocalVariablesUtil and fetchValues
    val frameProxy = context.frameProxy!!
    //val fetchedValues = LocalVariablesUtil.fetchValues(frameProxy, context.debugProcess, true) // works fine
    //val extractor = StackExtractor(frameProxy, context)
    //val managerThread = context.debugProcess
    //  .managerThread
    //managerThread.invokeCommand(extractor)
  }


  fun setSubscribedProcess(debugProcessImpl: DebugProcessImpl) {
    lastSubscribedProcess = debugProcessImpl
  }

  fun evaluationCompleted() {
    breakPointListener.evalStarted = false
    breakPointListener.debugEvalProcess.removeDebugProcessListener(breakPointListener)

    if (this::lastSubscribedProcess.isInitialized) {
      lastSubscribedProcess.removeEvaluationListener(this)
    }
    breakPointListener.evalStarted = false


    val service = service<DynamicInterpretationService>()
    val storage = service.resolvedStorage
    val entries = storage.entries
    LOG.debug("Collected data:\n")
    entries.forEach {
      LOG.debug("Method: ${it.key}, ${it.value.entries.forEach { it.toString() + "\t" }}")
    }
    val a = 1
  }


}