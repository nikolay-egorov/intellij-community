package com.jetbrains.python.codeInsight.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.QualifiedName
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil
import com.jetbrains.python.codeInsight.imports.AddImportHelper
import com.jetbrains.python.inspections.unresolvedReference.PyPackageAliasesProvider
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.resolve.PyResolveUtil
import com.jetbrains.python.psi.resolve.QualifiedNameFinder
import com.jetbrains.python.psi.search.PySearchUtilBase
import com.jetbrains.python.psi.stubs.PyModuleNameIndex
import com.jetbrains.python.psi.stubs.PyQualifiedNameCompletionMatcher
import com.jetbrains.python.psi.stubs.PyQualifiedNameCompletionMatcher.QualifiedNameMatcher

class PyUnresolvedModuleAttributeCompletionContributor : CompletionContributor() {

  private companion object {
    val UNRESOLVED_FIRST_COMPONENT = object : PatternCondition<PyReferenceExpression>("unresolved first component") {
      override fun accepts(expression: PyReferenceExpression, context: ProcessingContext): Boolean {
        val qualifier = context.get(REFERENCE_QUALIFIER)
        val qualifiersFirstComponent = qualifier.firstComponent ?: return false
        val scopeOwner = ScopeUtil.getScopeOwner(expression) ?: return false
        return PyResolveUtil.resolveLocally(scopeOwner, qualifiersFirstComponent).isEmpty()
      }
    }

    val QUALIFIED_REFERENCE_EXPRESSION = psiElement(PyTokenTypes.IDENTIFIER).withParent(
      psiElement(PyReferenceExpression::class.java)
        .andNot(psiElement().inside(PyImportStatementBase::class.java))
        .with(object : PatternCondition<PyReferenceExpression>("plain qualified name") {
          override fun accepts(expression: PyReferenceExpression, context: ProcessingContext): Boolean {
            if (!expression.isQualified) return false
            val qualifiedName = expression.asQualifiedName() ?: return false
            context.put(REFERENCE_QUALIFIER, qualifiedName.removeLastComponent())
            return true
          }
        })
        .with(UNRESOLVED_FIRST_COMPONENT)
    )

    val REFERENCE_QUALIFIER: Key<QualifiedName> = Key.create("QUALIFIER")

    private val functionInsertHandler: InsertHandler<LookupElement> = object : PyFunctionInsertHandler() {
      override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val tailOffset = context.tailOffset - 1
        super.handleInsert(context, item)  // adds parentheses, modifies tail offset
        context.commitDocument()
        addImportForLookupElement(context, item, tailOffset)
      }
    }

    private val importingInsertHandler: InsertHandler<LookupElement> = InsertHandler { context, item ->
      addImportForLookupElement(context, item, context.tailOffset - 1)
    }

    private fun addImportForLookupElement(context: InsertionContext, item: LookupElement, tailOffset: Int) {
      val manager = PsiDocumentManager.getInstance(context.project)
      val document = manager.getDocument(context.file)
      if (document != null) {
        manager.commitDocument(document)
      }
      val ref = context.file.findReferenceAt(tailOffset)

      WriteCommandAction.writeCommandAction(context.project, context.file).run<RuntimeException> {
        val psiElement = item.psiElement
        if (psiElement is PsiNamedElement && psiElement.containingFile != null) {
          val name = QualifiedName.fromDottedString(item.lookupString).removeLastComponent().toString()
          val commonAlias = PyPackageAliasesProvider.commonImportAliases[name]
          val nameToImport = commonAlias ?: name
          AddImportHelper.addImportStatement(context.file, nameToImport, if (commonAlias != null) name else null,
                                             AddImportHelper.getImportPriority(context.file, psiElement.containingFile),
                                             ref?.element as? PyElement)
        }
      }
    }

    private fun getInsertHandler(elementToInsert: PyElement, position: PsiElement): InsertHandler<LookupElement> {
      return if (elementToInsert is PyFunction && position.parent?.parent !is PyDecorator) functionInsertHandler
      else importingInsertHandler
    }
  }

  init {
    extend(CompletionType.BASIC, QUALIFIED_REFERENCE_EXPRESSION, object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val originalReferenceExpr = parameters.originalPosition?.parent as? PyReferenceExpression
        // It cannot be checked in the pattern, because the default placeholder splits the reference, e.g. "foo.ba<caret>IntellijIdeaRulezzz z".
        val isOtherReferenceQualifier = originalReferenceExpr?.parent is PyReferenceExpression
        if (isOtherReferenceQualifier) return

        val project = parameters.position.project
        val attribute = result.prefixMatcher.prefix
        val qualifier = context.get(REFERENCE_QUALIFIER)
        val suggestedQualifiedNames = HashSet<String>()

        ProgressManager.checkCanceled()
        val qualifiedName = qualifier.append(attribute)
        val packageNameForAlias = PyPackageAliasesProvider.commonImportAliases[qualifier.toString()]
        val packageName = if (packageNameForAlias != null) QualifiedName.fromDottedString(packageNameForAlias) else qualifier
        val resultMatchingCompleteReference = result.withPrefixMatcher(QualifiedNameMatcher(qualifiedName))
        PyModuleNameIndex.find(packageName.lastComponent!!, project, true).asSequence()
          .filter { QualifiedNameFinder.findShortestImportableQName(it) == packageName }
          .flatMap { it.iterateNames().asSequence() }
          .filter { it.containingFile != null }
          .filterNot { it is PsiFileSystemItem }
          .filterNot { it.name == null || it.name!!.startsWith('_') }
          .filter { attribute.isEmpty() || resultMatchingCompleteReference.prefixMatcher.prefixMatches(it.name!!) }
          .mapNotNull {
            val qualifiedNameToSuggest = "$qualifier.${it.name}"
            if (suggestedQualifiedNames.add(qualifiedNameToSuggest)) {
              LookupElementBuilder.create(it, qualifiedNameToSuggest)
                .withIcon(it.getIcon(0))
                .withInsertHandler(getInsertHandler(it, parameters.position))
                .withTypeText(packageNameForAlias)
            }
            else null
          }
          .forEach { resultMatchingCompleteReference.addElement(it) }

        if (attribute.isEmpty()) {
          result.restartCompletionOnAnyPrefixChange()
          return
        }
        val scope = PySearchUtilBase.defaultSuggestionScope(parameters.originalFile)
        PyQualifiedNameCompletionMatcher.processMatchingExportedNames(
          packageName.append(attribute), if (packageNameForAlias != null) qualifiedName else null, parameters.originalFile, scope,
          Processor {
            ProgressManager.checkCanceled()
            if (suggestedQualifiedNames.add(it.qualifiedName.toString())) {
              resultMatchingCompleteReference.addElement(LookupElementBuilder
                                                           .createWithSmartPointer(it.qualifiedNameWithUserTypedAlias.toString(),
                                                                                   it.element)
                                                           .withIcon(it.element.getIcon(0))
                                                           .withInsertHandler(getInsertHandler(it.element, parameters.position)))
            }
            return@Processor true
          })
      }
    })
  }

}