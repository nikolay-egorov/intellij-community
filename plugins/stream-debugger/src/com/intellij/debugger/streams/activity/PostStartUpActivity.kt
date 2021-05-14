// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.activity

import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.streams.inspect.InspectingDebugListener
import com.intellij.debugger.streams.inspect.service.RequesterStorageService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener


class PostStartUpActivity : StartupActivity {
  override fun runActivity(project: Project) {
    attachDebugStartListener(project)
  }


  private fun attachDebugStartListener(project: Project) {
    project.messageBus.connect().subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
      override fun processStarted(debugProcess: XDebugProcess) {
        attachDebugBreakListener(debugProcess)
      }

      //override fun currentSessionChanged(previousSession: XDebugSession?, currentSession: XDebugSession?) {
      //  changeSession(previousSession, currentSession)
      //}
    })
  }

  private fun changeSession(previousSession: XDebugSession?, currentSession: XDebugSession?) {
    val service = service<RequesterStorageService>()

    if (service.currentSession == null || service.currentSession != currentSession) {
      service.currentSession = currentSession
      if (currentSession != null) {
        service.project = currentSession.project
      }
    }
  }

  private fun attachDebugBreakListener(debugProcess: XDebugProcess) {
    //debugProcess.session.addSessionListener(object : XDebugSessionListener {
    //  override fun sessionPaused() {
    //    attachComputeChildrenListener(debugProcess.session.currentStackFrame)
    //  }
    //})
    val project = debugProcess.session.project
    val debuggerManager = DebuggerManager.getInstance(project)
    val handler = debugProcess.processHandler
    val myNewListener = InspectingDebugListener()
    val session = debugProcess.session

    val frame = session.currentStackFrame
    myNewListener.xSession = session?: return
    session.addSessionListener(myNewListener)



    //debuggerManager.getDebugProcess(handler).addDebugProcessListener(myNewListener)
    val service = service<RequesterStorageService>()
    service.project = project
  }

}