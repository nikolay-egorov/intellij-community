// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.storage

import com.intellij.debugger.streams.inspect.represent.VarInfo
import com.intellij.psi.controlFlow.ControlFlowUtil.VariableInfo
import com.sun.jdi.Field
import com.sun.jdi.LocalVariable
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.collections.List
import kotlin.collections.MutableMap
import kotlin.collections.set


class DebugHistory {

  private val vars: MutableMap<LocalVariable, LinkedList<VarInfo>?> = LinkedHashMap()


  private val fields: MutableMap<Field, LinkedList<VarInfo>?> = LinkedHashMap()


  fun getHistory(vr: Any?): LinkedList<VarInfo>? {
    if (vr == null) return null
    if (vr is LocalVariable) {
      return vars[vr]!!.clone() as LinkedList<VarInfo>
    }
    else if (vr is Field) {
      return fields[vr]!!.clone() as LinkedList<VarInfo>
    }
    return null
  }


  val allVariables: List<LocalVariable>
    get() = ArrayList(vars.keys)


  val allFields: List<Field>
    get() = ArrayList(fields.keys)


  fun getMostRecentUpdate(vr: LocalVariable): VarInfo? {
    return if (vars.containsKey(vr)) {
      vars[vr]!!.getLast()
    }
    else null
  }


  fun put(vr: LocalVariable, update: VarInfo) {
    var info: LinkedList<VarInfo>? = vars[vr]
    if (info == null) {
      info = LinkedList()
    }
    if (info.isEmpty() || update != info.getLast()) {
      info.add(update)
      vars[vr] = info
     }
  }


  fun put(field: Field, update: VarInfo) {
    var info: LinkedList<VarInfo>? = fields[field]
    if (info == null) {
      info = LinkedList()
    }
    if (info.isEmpty() || update != info.getLast()) {
      info.add(update)
      fields[field] = info
    }
  }


  fun clear() {
    vars.clear()
    fields.clear()
  }

  override fun toString(): String {
    var res = "CACHE:\n\n"
    for (`var` in vars.keys) {
      res += "\t ${`var`.name()} "
      res += "\t ${vars[`var`].toString()} "
    }
    for (f in fields.keys) {
      res += "\t  ${f.name()} "
      res += "\t ${fields[f].toString()} "
    }
    return res
  }

  fun size(): Int {
    return vars.size + fields.size
  }

}