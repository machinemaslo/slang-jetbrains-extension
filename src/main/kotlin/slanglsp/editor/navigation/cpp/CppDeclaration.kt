package slanglsp.editor.navigation.cpp

import com.intellij.openapi.vfs.VirtualFile

internal data class CppDeclaration(val type: String, val location: CppLocation)

internal data class CppLocation(val file: VirtualFile, val stamp: Long, val offset: Int, val line: Int)
