// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.action;

import com.intellij.debugger.engine.evaluation.EvaluationContextImpl;
import com.intellij.debugger.streams.diagnostic.ex.TraceCompilationException;
import com.intellij.debugger.streams.diagnostic.ex.TraceEvaluationException;
import com.intellij.debugger.streams.inspect.service.RequesterStorageService;
import com.intellij.debugger.streams.lib.LibrarySupportProvider;
import com.intellij.debugger.streams.psi.DebuggerPositionResolver;
import com.intellij.debugger.streams.psi.impl.DebuggerPositionResolverImpl;
import com.intellij.debugger.streams.trace.*;
import com.intellij.debugger.streams.trace.impl.TraceResultInterpreterImpl;
import com.intellij.debugger.streams.trace.impl.handler.reactive.ReactorOnSignalCall;
import com.intellij.debugger.streams.ui.ChooserOption;
import com.intellij.debugger.streams.ui.impl.ElementChooserImpl;
import com.intellij.debugger.streams.ui.impl.EvaluationAwareTraceWindow;
import com.intellij.debugger.streams.wrapper.StreamChain;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Stream;

/**
 * @author Vitaliy.Bibaev
 */
public final class TraceStreamAction extends AnAction {
  private static final Logger LOG = Logger.getInstance(TraceStreamAction.class);

  private static final ChainResolver CHAIN_RESOLVER = new ChainResolver();
  private final DebuggerPositionResolver myPositionResolver = new DebuggerPositionResolverImpl();

  private boolean tappedTwiceForReactive = true;
  private static final List<String> excludes = List.of("java.*", "javax.*", "sun.*", "com.sun.*");

  @Override
  public void update(@NotNull AnActionEvent e) {
    final XDebugSession session = getCurrentSession(e);
    final PsiElement element = session == null ? null : myPositionResolver.getNearestElementToBreakpoint(session);
    final Presentation presentation = e.getPresentation();
    if (element == null) {
      presentation.setVisible(true);
      presentation.setEnabled(false);
    }
    else {
      ChainResolver.ChainStatus chainStatus = CHAIN_RESOLVER.tryFindChain(element);
      switch (chainStatus) {
        case LANGUAGE_NOT_SUPPORTED:
          presentation.setEnabledAndVisible(false);
          break;
        case FOUND:
        case REACTIVE_NON_TERMINAL_FOUND:
          var service = ServiceManager.getService(RequesterStorageService.class);
          var editor = e.getData(LangDataKeys.EDITOR);
          if (editor == null) {
            presentation.setEnabledAndVisible(true);
            break;
          }
          var doc = editor.getDocument();
          var currentFile = FileDocumentManager.getInstance().getFile(doc);
          var chains = CHAIN_RESOLVER.getChains(element);
          if (currentFile != null && !chains.isEmpty()) {
            service.addNewChain(currentFile, chains.get(0).chain);
          }
          presentation.setEnabledAndVisible(true);
          break;
        case COMPUTING:
        case NOT_FOUND:
          presentation.setVisible(true);
          presentation.setEnabled(false);
          break;
      }
    }
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    XDebugSession session = getCurrentSession(e);
    LibrarySupportProvider.EP_NAME.getExtensionList();
    XSourcePosition position = session == null ? null : session.getCurrentPosition();
    PsiElement element = session == null ? null : myPositionResolver.getNearestElementToBreakpoint(session);

    if (element == null || position == null) {
      LOG.info("element at cursor not found");
      return;
    }

    List<ChainResolver.StreamChainWithLibrary> chains = CHAIN_RESOLVER.getChains(element);
    if (chains.isEmpty()) {
      LOG.warn("stream chain is not built");
      return;
    }

    if (chains.size() == 1) {
      if (chains.get(0).chain.getTerminationCall() instanceof ReactorOnSignalCall.AbsentTerminationCall && tappedTwiceForReactive) {
        Messages.showMessageDialog(
          e.getProject(), "Double click if you want to debug it. Note it will try to execute stream in a cold manner",
          "Reactive stream with no termination call found", Messages.getInformationIcon()
        );
        tappedTwiceForReactive = false;
        return;
      }
      runTrace(chains.get(0).chain, chains.get(0).provider, session);
      tappedTwiceForReactive = true;
    }
    else {
      Project project = session.getProject();
      VirtualFile file = position.getFile();
      Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
      LOG.assertTrue(editor != null);
      ApplicationManager.getApplication()
        .invokeLater(() -> {
          new MyStreamChainChooser(editor).show(ContainerUtil.map(chains, StreamChainOption::new),
                                                provider -> runTrace(provider.chain, provider.provider, session));
        });
    }
  }

  private static void runTrace(@NotNull StreamChain chain, @NotNull LibrarySupportProvider provider, @NotNull XDebugSession session) {
    final EvaluationAwareTraceWindow window = new EvaluationAwareTraceWindow(session, chain);
    ApplicationManager.getApplication().invokeLater(window::show);
    final Project project = session.getProject();
    //final var evaluationListener = new EvaluationListener(excludes);
    //XDebugSession currentSession = XDebuggerManager.getInstance(project).getCurrentSession();
    // old experiments
    //currentSession.getContextManager().addListener(breakPListener);
    //currentSession.getProcess().addEvaluationListener(evaluationListener);
    //evaluationListener.setSubscribedProcess(currentSession.getProcess());
    //evaluationListener.setChain(chain);

    final TraceExpressionBuilder expressionBuilder = provider.getExpressionBuilder(project);
    final TraceResultInterpreterImpl resultInterpreter = new TraceResultInterpreterImpl(provider.getLibrarySupport().getInterpreterFactory());
    final StreamTracer tracer = new EvaluateExpressionTracer(session, expressionBuilder, resultInterpreter);
    tracer.trace(chain, new TracingCallback() {
      @Override
      public void evaluated(@NotNull TracingResult result, @NotNull EvaluationContextImpl context) {
        final ResolvedTracingResult resolvedTrace = result.resolve(provider.getLibrarySupport().getResolverFactory());
        ApplicationManager.getApplication()
          .invokeLater(() -> window.setTrace(resolvedTrace, context));
        //evaluationListener.evaluationCompleted();
      }

      @Override
      public void evaluationFailed(@NotNull String traceExpression, @NotNull String message) {
        notifyUI(message);
        //evaluationListener.evaluationCompleted();
        throw new TraceEvaluationException(message, traceExpression);
      }

      @Override
      public void compilationFailed(@NotNull String traceExpression, @NotNull String message) {
        notifyUI(message);
        //evaluationListener.evaluationCompleted();
        throw new TraceCompilationException(message, traceExpression);
      }

      private void notifyUI(@NotNull @Nls String message) {
        ApplicationManager.getApplication().invokeLater(() -> window.setFailMessage(message));
      }
    });
  }

  @Nullable
  private static XDebugSession getCurrentSession(@NotNull AnActionEvent e) {
    final Project project = e.getProject();
    return project == null ? null : XDebuggerManager.getInstance(project).getCurrentSession();
  }

  private static final class MyStreamChainChooser extends ElementChooserImpl<StreamChainOption> {
    MyStreamChainChooser(@NotNull Editor editor) {
      super(editor);
    }
  }

  private static final class StreamChainOption implements ChooserOption {
    final StreamChain chain;
    final LibrarySupportProvider provider;

    StreamChainOption(@NotNull ChainResolver.StreamChainWithLibrary chain) {
      this.chain = chain.chain;
      this.provider = chain.provider;
    }

    @NotNull
    @Override
    public Stream<TextRange> rangeStream() {
      return Stream.of(
        new TextRange(chain.getQualifierExpression().getTextRange().getStartOffset(),
                      chain.getTerminationCall().getTextRange().getEndOffset()));
    }

    @NotNull
    @Override
    public String getText() {
      return chain.getCompactText();
    }
  }
}
