package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.google.common.util.concurrent.SettableFuture;

import io.grpc.Channel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorStub;

public class MirrorClient {

  public static void main(String[] args) throws Exception {
    LoggingConfig.init();
    Path root = Paths.get(args[0]).toAbsolutePath();
    String host = args[1];
    Integer port = Integer.parseInt(args[2]);
    Channel c = NettyChannelBuilder.forAddress(host, port).negotiationType(NegotiationType.PLAINTEXT).build();
    MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");
    MirrorClient client = new MirrorClient(root);
    client.startSession(stub);
    // TODO something better
    CountDownLatch cl = new CountDownLatch(1);
    cl.await();
  }

  private final Path root;
  private MirrorSession session;

  public MirrorClient(Path root) {
    this.root = root;
  }

  /** Connects to the server and starts a sync session. */
  public void startSession(MirrorStub stub) {
    session = new MirrorSession(root);

    try {
      // 1. see what our current state is
      List<Update> localState = session.calcInitialState();

      // 2. send it to the server, so they can send back any stale/missing paths we have
      SettableFuture<PathState> remoteState = SettableFuture.create();
      stub.initialSync(InitialSyncRequest.newBuilder().addAllState(localState).build(), new StreamObserver<InitialSyncResponse>() {
        @Override
        public void onNext(InitialSyncResponse value) {
          remoteState.set(new PathState(value.getStateList()));
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
      });

      session.setInitialRemoteState(remoteState.get());
      session.seedQueueForInitialSync(new PathState(localState));

      StreamObserver<Update> incomingChanges = new StreamObserver<Update>() {
        @Override
        public void onNext(Update update) {
          session.addRemoteUpdate(update);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
      };

      StreamObserver<Update> outgoingChanges = stub.streamUpdates(incomingChanges);

      session.startPolling(outgoingChanges);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void stop() throws InterruptedException {
    session.stop();
  }
}