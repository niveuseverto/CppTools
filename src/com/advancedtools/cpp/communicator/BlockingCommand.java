/* AdvancedTools, 2007, all rights reserved */
package com.advancedtools.cpp.communicator;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;

import javax.swing.*;

/**
 * @author maxim
 */
public abstract class BlockingCommand extends CommunicatorCommand {
  protected volatile boolean ready;
  protected volatile boolean failed;
  protected volatile boolean cancelled;
  protected boolean doInfiniteBlockingWithCancelledCheck;
  static final int MS_TO_WAIT = 100;

  public boolean hasReadyResult() {
    return ready;
  }

  public void doExecute() {
    synchronized(this) {
      ready = true;
      notifyAll();
    }
  }

  public void doExecuteOnCancel() {
    synchronized(this) {
      failed=true;
      notifyAll();
    }
  }

  public void nonblockingPost(Project project) {
    super.post(project);
  }

  public void post(final Project project) {
    if (project.isDisposed()) {
      cancelled = true;
      return;
    }

    try {
      super.post(project);
      if (doInfiniteBlockingWithCancelledCheck) {

        await(project);
        return;
      }
      boolean continueWait = false;
      final boolean eventDispatchThread = SwingUtilities.isEventDispatchThread();

      synchronized(this) {
        try {
          wait(eventDispatchThread ? 1000:5000);
          if (!ready && !failed) {
            continueWait = true;
          }
        } catch(InterruptedException e) { return; }
      }

      if (continueWait) {
        if (!eventDispatchThread) {
          synchronized(this) {
            cancelled = true;
            return;
          }
        }

        final boolean pressedCancel = ProgressManager.getInstance().runProcessWithProgressSynchronously(
          new Runnable() {
            public void run() {
              while (true) {
                synchronized(BlockingCommand.this) {
                  if (ready || failed) return;
                  try {
                    BlockingCommand.this.wait(500);
                  } catch (InterruptedException ex) {
                    return;
                  }
                }
                ProgressManager.checkCanceled();
              }
            }
          },
          getCommand(),
          true,
          project
        );

        synchronized(this) {
          if (!ready && !failed || pressedCancel) cancelled = true;
        }
      }
    } catch (RuntimeException e) {
      if (e instanceof ProcessCanceledException) {
        if (!SwingUtilities.isEventDispatchThread()) throw e;
      }

      e.printStackTrace();
    }
  }

  public void await(Project project) {
    await(project, defaultPollingConditional);
  }

  protected interface PollingConditional {
    boolean isReady();
  }

  protected final PollingConditional defaultPollingConditional = new PollingConditional() {
    public boolean isReady() {
      return ready;
    }
  };

  public void await(Project project, PollingConditional conditional) {
    final Communicator communicator = Communicator.getInstance(project);
    int restartCountWhenInReadAction = communicator.getRestartCountWhenInReadAction();
    int max = restartCountWhenInReadAction;
    boolean readAccessAllowed = ApplicationManager.getApplication().isReadAccessAllowed();

    while(true) {
      synchronized(this) {
        if (conditional.isReady() || failed) break;

        try { wait(MS_TO_WAIT); }
        catch(InterruptedException e) {
          //System.out.println("Interrupted");
        }
        if (conditional.isReady() || failed) {
          break;
        }
      }
      try {
        ProgressManager.checkCanceled();
        --max;
        if (max == 0 && readAccessAllowed) {
          communicator.decreaseRestartCountWhenInReadAction(restartCountWhenInReadAction);
          System.out.println("Terminating long command in read action, no answer:" + (restartCountWhenInReadAction* MS_TO_WAIT) + " command:"+ getCommand());
          throw new ProcessCanceledException();
        }
      } catch(ProcessCanceledException ex) {
        communicator.cancelCommand(this);
        throw ex;
      }
    }
  }

  public void setDoInfiniteBlockingWithCancelledCheck(boolean doInfiniteBlockingWithCancelledCheck) {
    this.doInfiniteBlockingWithCancelledCheck = doInfiniteBlockingWithCancelledCheck;
  }

  public synchronized boolean isFailedOrCancelled() {
    return failed || cancelled;
  }
}
