// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.inspect.represent

class VarInfo(val line: Int, val value: String) {

  override fun equals(other: Any?): Boolean {
     if (other === this) {
      return true
    }

    if (other !is VarInfo) {
      return false
    }

    val c  = other as VarInfo

     return this.value == c.value
  }

  override fun toString(): String {
    return "line: $line, val: $value"
  }

}