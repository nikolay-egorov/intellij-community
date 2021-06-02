// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect

import com.intellij.debugger.engine.jdi.StackFrameProxy
import com.intellij.openapi.util.Pair
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference

class StackWalker {

  companion object {

    fun resolveTracingFrame(stackFrameProxy: StackFrameProxy) : Pair<ThreadReference, StackFrameProxy> {
      val threadProxy = stackFrameProxy.threadProxy()
      val threadReference = threadProxy.threadReference

      val frameCount = threadProxy.frameCount()
      val currentFrame =  stackFrameProxy.stackFrame
      var startInd = stackFrameProxy.frameIndex
      var traverseableStackFrame = threadProxy.frame(startInd--)
      while (startInd > 0) {
        traverseableStackFrame = threadProxy.frame(startInd)
        val location = traverseableStackFrame.location()
        location.sourceName()
      }
      return Pair.pair(threadReference, traverseableStackFrame)
    }
  }
}