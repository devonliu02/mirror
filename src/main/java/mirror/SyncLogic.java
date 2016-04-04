package mirror;

import static mirror.Utils.debugString;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.grpc.stub.StreamObserver;

/**
 * Implements the steady-state (post-initial sync) two-way sync logic.
 *
 * We poll for changes, either the remote host or our local disk, and
 * either persist it locally or send it out remotely, while also considering
 * whether we've since had a newer/conflicting change.
 */
public class SyncLogic {

  private static final Logger log = LoggerFactory.getLogger(SyncLogic.class);
  private static final String poisonPillPath = "SHUTDOWN NOW";
  private final String role;
  private final BlockingQueue<Update> changes;
  private final StreamObserver<Update> outgoing;
  private final FileAccess fileAccess;
  private final UpdateTree localTree;
  private final UpdateTree remoteTree;
  private volatile boolean shutdown = false;
  private final CountDownLatch isShutdown = new CountDownLatch(1);

  public SyncLogic(
    String role,
    BlockingQueue<Update> changes,
    StreamObserver<Update> outgoing,
    FileAccess fileAccess,
    UpdateTree localTree,
    UpdateTree remoteTree) {
    this.role = role;
    this.changes = changes;
    this.outgoing = outgoing;
    this.fileAccess = fileAccess;
    this.localTree = localTree;
    this.remoteTree = remoteTree;
  }

  /**
   * Starts polling for changes.
   *
   * Polling happens on a separate thread, so this method does not block.
   */
  public void startPolling() {
    Runnable runnable = () -> {
      try {
        pollLoop();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // TODO need to signal that our connection needs reset
        throw new RuntimeException(e);
      }
    };
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("SyncLogic-" + role + "-%s").build().newThread(runnable).start();
  }

  public void stop() throws InterruptedException {
    shutdown = true;
    changes.clear();
    changes.add(Update.newBuilder().setPath(poisonPillPath).build());
    isShutdown.await();
  }

  private void pollLoop() throws InterruptedException {
    while (!shutdown) {
      Update u = changes.take();
      try {
        handleUpdate(u);
      } catch (Exception e) {
        log.error(role + " exception handling " + debugString(u), e);
      }
    }
    isShutdown.countDown();
  }

  @VisibleForTesting
  public void poll() throws IOException, InterruptedException {
    Update u = changes.poll();
    if (u != null) {
      handleUpdate(u);
    }
  }

  private void handleUpdate(Update u) throws IOException, InterruptedException {
    if (u.getPath().equals(poisonPillPath)) {
      outgoing.onCompleted();
      return;
    }
    if (u.getPath().startsWith("STATUS:")) {
      // this is kind of hacky...
      log.info(role + " " + u.getPath().replace("STATUS:", ""));
    } else if (u.getLocal()) {
      handleLocal(u);
    } else {
      handleRemote(u);
    }
  }

  private void handleLocal(Update local) throws IOException, InterruptedException {
    if (!isStaleLocalUpdate(local)) {
      localTree.add(readLatestTimeAndSymlink(local));
      diff();
    }
  }

  private void handleRemote(Update remote) throws IOException {
    remoteTree.add(remote);
    diff();
  }

  private void diff() {
    new UpdateTreeDiff(localTree, remoteTree, new DiffApplier(role, outgoing, fileAccess)).diff();
  }

  /**
   * If we're changing the type of a node, e.g. from a file to a directory,
   * we'll delete the file, which will create a delete event in FileWatcher,
   * but we'll have already created the directory, so we can ignore this
   * update. (Files could also disappear in general between FileWatcher
   * putting the update in the queue, and us picking it up, but that should
   * be rarer.)
   */
  private boolean isStaleLocalUpdate(Update local) throws IOException {
    Path path = Paths.get(local.getPath());
    boolean stillDeleted = local.getDelete() && fileAccess.exists(path);
    boolean stillExists = !local.getDelete() && !fileAccess.exists(path);
    return stillDeleted || stillExists;
  }

  /**
   * Even though FileWatcher sets mod times, we need to re-read the mod time here,
   * because if we saved a file, we technically have to a) first write the file, then
   * b) set the mod time back in time to what matches the remote (to prevent the local
   * file from looking newer than the remote that it's actually a copy of).
   * 
   * The FileWatcher is fast enough that it could actually read a "too new" mod time
   * in between a) and b).
   */
  private Update readLatestTimeAndSymlink(Update local) throws IOException {
    if (!local.getDelete()) {
      try {
        local = Update.newBuilder(local).setModTime(fileAccess.getModifiedTime(Paths.get(local.getPath()))).build();
        if (!local.getSymlink().isEmpty()) {
          local = Update.newBuilder(local).setSymlink(fileAccess.readSymlink(Paths.get(local.getPath())).toString()).build();
        }
      } catch (IOException e) {
        // ignore as the path was probably deleted
      }
    }
    return local;
  }

}
