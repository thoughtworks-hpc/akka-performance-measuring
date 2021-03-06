package com.thoughtworks.hpc.akka.performance.measuring;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Function;

public class RootActor extends AbstractBehavior<RootActor.Command> {

    private final Logger logger;

    public static abstract class Command {
        final int n;
        final CountDownLatch finish;


        protected Command(int n) {
            this.n = n;
            finish = new CountDownLatch(1);
        }
    }

    public static class HandleEnqueueing extends Command {
        public HandleEnqueueing(int n) {
            super(n);
        }
    }


    public static class HandleDequeueing extends Command {
        public HandleDequeueing(int n) {
            super(n);
        }
    }

    public static class HandleInitiation extends Command {
        public HandleInitiation(int n) {
            super(n);
        }
    }

    public static class HandleSingleProducerSending extends Command {
        public HandleSingleProducerSending(int n) {
            super(n);
        }
    }

    public static class HandleMultiProducerSending extends Command {
        private final int parallelism;

        public HandleMultiProducerSending(int n, int parallelism) {
            super(n);
            this.parallelism = parallelism;
        }
    }

    public static class HandleMaxThroughput extends Command {
        private final int parallelism;

        public HandleMaxThroughput(int n, int parallelism) {
            super(n);
            this.parallelism = parallelism;
        }
    }

    public static class HandlePingLatency extends Command {
        public HandlePingLatency(int n) {
            super(n);
        }
    }

    public static class HandlePingThroughput extends Command {
        private final int pairCount;

        public HandlePingThroughput(int n, int pairCount) {
            super(n);
            this.pairCount = pairCount;
        }
    }

    private RootActor(ActorContext<Command> context) {
        super(context);
        logger = getContext().getLog();
    }

    public static Behavior<Command> create() {
        return Behaviors.setup(RootActor::new);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(HandleEnqueueing.class, this::onHandleEnqueueing)
                .onMessage(HandleDequeueing.class, this::onHandleDequeueing)
                .onMessage(HandleInitiation.class, this::onHandleInitiation)
                .onMessage(HandleMultiProducerSending.class, this::onHandleMultiProducerSending)
                .onMessage(HandleSingleProducerSending.class, this::onHandleSingleProducerSending)
                .onMessage(HandleMaxThroughput.class, this::onHandleMaxThroughput)
                .onMessage(HandlePingLatency.class, this::onHandlePingLatency)
                .onMessage(HandlePingThroughput.class, this::onHandlePingThroughput)
                .build();
    }

    private int roundToEven(int x) {
        if ((x & 1) == 1) {
            x--;
        }
        return x;
    }

    private long timed(Function<Void, Void> f) {
        long start = System.nanoTime();
        f.apply(null);
        return System.nanoTime() - start;
    }

    private Behavior<Command> onHandlePingThroughput(HandlePingThroughput handlePingThroughput) {
        int p = roundToEven(handlePingThroughput.pairCount);
        int n = roundToParallelism(handlePingThroughput.n, p);
        CountDownLatch finishLatch = new CountDownLatch(p * 2);
        List<ActorRef<PingThroughputActor.Command>> actors = new ArrayList<>(p * 2);

        for (int i = 0; i < p; i++) {
            ActorRef<PingThroughputActor.Command> actor1 = getContext().spawnAnonymous(PingThroughputActor.create(finishLatch, n / p / 2));
            ActorRef<PingThroughputActor.Command> actor2 = getContext().spawnAnonymous(PingThroughputActor.create(finishLatch, n / p / 2));
            actors.add(actor1);
            actors.add(actor2);
        }

        long spentTime = timed((Void) -> {
            for (int i = 0; i < actors.size(); i += 2) {
                actors.get(i).tell(new PingThroughputActor.PingThroughputMessage(actors.get(i + 1)));
            }
            try {
                finishLatch.await();
            } catch (InterruptedException e) {
                logger.error(e.toString());
            }
            return null;
        });

//        System.out.println("Ping throughput:");
//        System.out.printf("\t%d ops\n", n);
//        System.out.printf("\t%d pairs\n", p);
//        System.out.printf("\t%d ns\n", spentTime);
//        System.out.printf("\t%d ops/s\n", n * 1000_000_000L / spentTime);
        String result = String.format("Ping throughput:\n\t%d ops\n\t%d pairs\n\t%d ns\n\t%d ops/s\n", n, p, spentTime, n * 1000_000_000L / spentTime);
        System.out.println(result);
        handlePingThroughput.finish.countDown();

        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result);
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

    private Behavior<Command> onHandlePingLatency(HandlePingLatency handlePingLatency) {
        int n = roundToEven(handlePingLatency.n);
        CountDownLatch finishLatch = new CountDownLatch(2);
        LatencyHistogram latencyHistogram = new LatencyHistogram();

        ActorRef<PingLatencyActor.Command> actor1 = getContext().spawnAnonymous(PingLatencyActor.create(finishLatch, n / 2, latencyHistogram));
        ActorRef<PingLatencyActor.Command> actor2 = getContext().spawnAnonymous(PingLatencyActor.create(finishLatch, n / 2, latencyHistogram));

        long spentTime = timed((notUsed) -> {
            actor1.tell(new PingLatencyActor.PingLatencyMessage(actor2));
            try {
                finishLatch.await();
            } catch (InterruptedException e) {
                logger.error(e.toString());
            }
            return null;
        });

        List<Double> percentileList = Arrays.asList(0.0, 0.5, 0.9, 0.99, 0.999, 0.9999, 1.0);
        StringBuilder result = new StringBuilder(String.format("Ping latency:\n\t%d ops\n\t%d ns\n", n, spentTime));
        for (Double x: percentileList){
            result.append(String.format("\tp(%1.5f) = %8d ns/op\n", x, latencyHistogram.getValueAtPercentile(x * 100)));
        }
//        System.out.println("Ping latency:");
//        System.out.printf("\t%d ops\n", n);
//        System.out.printf("\t%d ns\n", spentTime);
//        Arrays.asList(0.0, 0.5, 0.9, 0.99, 0.999, 0.9999, 1.0).forEach(
//                x -> System.out.printf("\tp(%1.5f) = %8d ns/op\n", x, latencyHistogram.getValueAtPercentile(x * 100))
//        );

        System.out.println(result);

        handlePingLatency.finish.countDown();
        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result.toString());
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

    private Behavior<Command> onHandleMaxThroughput(HandleMaxThroughput handleMaxThroughput) throws BrokenBarrierException, InterruptedException {
        int parallelism = handleMaxThroughput.parallelism;
        int n = roundToParallelism(handleMaxThroughput.n, parallelism);
        CountDownLatch finishLatch = new CountDownLatch(parallelism);

        CyclicBarrier barrier = new CyclicBarrier(parallelism + 1);
        ArrayList<Thread> threads = new ArrayList<>(parallelism);
        CountActor.EmptyMessage emptyMessage = new CountActor.EmptyMessage();
        int times = n / parallelism;
        for (int i = 0; i < parallelism; i++) {
            ActorRef<CountActor.Command> actor = getContext().spawnAnonymous(CountActor.create(finishLatch, times));
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    logger.error(e.toString());
                }
                for (int j = 0; j < times; j++) {
                    actor.tell(emptyMessage);
                }
            });
            thread.start();
            threads.add(thread);
        }

        long spentTime = timed((notUsed) -> {
            try {
                barrier.await();
                finishLatch.await();
            } catch (Exception e) {
                logger.error(e.toString());
            }
            return null;
        });

//        System.out.println("Max throughput:");
//        System.out.printf("\t%d ops\n", n);
//        System.out.printf("\t%d ns\n", spentTime);
//        System.out.printf("\t%d ops/s\n", n * 1000_000_000L / spentTime);
//        handleMaxThroughput.finish.countDown();
        String result = String.format("Max throughput:\n\t%d ops\n\t%d ns\n\t%d ops/s\n", n, spentTime, n * 1000_000_000L / spentTime);
        System.out.println(result);
        handleMaxThroughput.finish.countDown();

        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result);
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

    private int roundToParallelism(int n, int parallelism) {
        return (n / parallelism) * parallelism;
    }

    private Behavior<Command> onHandleMultiProducerSending(HandleMultiProducerSending handleMultiProducerSending) throws BrokenBarrierException, InterruptedException {
        CountDownLatch finishLatch = new CountDownLatch(1);
        int parallelism = handleMultiProducerSending.parallelism;
        int n = roundToParallelism(handleMultiProducerSending.n, parallelism);
        ActorRef<CountActor.Command> actor = getContext().spawnAnonymous(CountActor.create(finishLatch, n));
        CyclicBarrier barrier = new CyclicBarrier(parallelism + 1);
        List<Thread> threads = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    logger.error(e.toString());
                }
                int messageCount = n / parallelism;
                CountActor.EmptyMessage emptyMessage = new CountActor.EmptyMessage();
                for (int j = 0; j < messageCount; j++) {
                    actor.tell(emptyMessage);
                }
            });
            thread.start();
            threads.add(thread);
        }

        long spentTime = timed((notUsed) -> {
            try {
                barrier.await();
                finishLatch.await();
            } catch (Exception e) {
                logger.error(e.toString());
            }
            return null;
        });

//        System.out.println("Multi-producer sending:");
//        System.out.printf("\t%d ops\n", n);
//        System.out.printf("\t%d ns\n", spentTime);
//        System.out.printf("\t%d ops/s\n", n * 1000_000_000L / spentTime);
//        handleMultiProducerSending.finish.countDown();

        String result = String.format("Multi-producer sending:\n\t%d ops\n\t%d ns\n\t%d ops/s\n", n, spentTime, n * 1000_000_000L / spentTime);
        System.out.println(result);
        handleMultiProducerSending.finish.countDown();

        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result);
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

    private Behavior<Command> onHandleSingleProducerSending(HandleSingleProducerSending handleSingleProducerSending) throws InterruptedException {
        CountDownLatch finishLatch = new CountDownLatch(1);
        ActorRef<CountActor.Command> actor = getContext().spawnAnonymous(CountActor.create(finishLatch, handleSingleProducerSending.n));

        long spentTime = timed((notUsed) -> {
            CountActor.EmptyMessage emptyMessage = new CountActor.EmptyMessage();
            for (int i = 0; i < handleSingleProducerSending.n; i++) {
                actor.tell(emptyMessage);
            }
            try {
                finishLatch.await();
            } catch (InterruptedException e) {
                logger.error(e.toString());
            }
            return null;
        });

//        System.out.println("Single-producer sending:");
//        System.out.printf("\t%d ops\n", handleSingleProducerSending.n);
//        System.out.printf("\t%d ns\n", spentTime);
//        System.out.printf("\t%d ops/s\n", handleSingleProducerSending.n * 1000_000_000L / spentTime);
//        handleSingleProducerSending.finish.countDown();

        String result = String.format("Single-producer sending:\n\t%d ops\n\t%d ns\n\t%d ops/s\n", handleSingleProducerSending.n, spentTime, handleSingleProducerSending.n * 1000_000_000L / spentTime);
        System.out.println(result);
        handleSingleProducerSending.finish.countDown();

        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result);
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

    private Behavior<Command> onHandleInitiation(HandleInitiation handleInitiation) {
        List<ActorRef<MinimalActor.Command>> actors = new ArrayList<>(handleInitiation.n);

        long spentTime = timed((notUsed) -> {
            for (int i = 0; i < handleInitiation.n; i++) {
                ActorRef<MinimalActor.Command> actorRef = getContext().spawnAnonymous(MinimalActor.create());
                actors.add(actorRef);
            }
            return null;
        });

        // tear down
        for (ActorRef<MinimalActor.Command> actor : actors) {
            getContext().stop(actor);
        }

//        System.out.println("Initiation:");
//        System.out.printf("\t%d ops\n", handleInitiation.n);
//        System.out.printf("\t%d ns\n", spentTime);
//        System.out.printf("\t%d ops/s\n", handleInitiation.n * 1000_000_000L / spentTime);
//        handleInitiation.finish.countDown();

        String result = String.format("Initiation:\n\t%d ops\n\t%d ns\n\t%d ops/s\n", handleInitiation.n, spentTime, handleInitiation.n * 1000_000_000L / spentTime);
        System.out.println(result);
        handleInitiation.finish.countDown();

        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result);
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

    private Behavior<Command> onHandleDequeueing(HandleDequeueing handleDequeueing) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);
        ActorRef<BlockableCountActor.Command> actor = getContext().spawnAnonymous(BlockableCountActor.create(startLatch, finishLatch, handleDequeueing.n));

        BlockableCountActor.EmptyMessage message = new BlockableCountActor.EmptyMessage();
        for (int i = 0; i < handleDequeueing.n; i++) {
            actor.tell(message);
        }

        long spentTime = timed((notUsed) -> {
            startLatch.countDown();
            try {
                finishLatch.await();
            } catch (InterruptedException e) {
                logger.error(e.toString());
            }
            return null;
        });

//        System.out.println("Dequeueing:");
//        System.out.printf("\t%d ops\n", handleDequeueing.n);
//        System.out.printf("\t%d ns\n", spentTime);
//        System.out.printf("\t%d ops/s\n", handleDequeueing.n * 1000_000_000L / spentTime);
//        handleDequeueing.finish.countDown();

        String result = String.format("Dequeueing:\n\t%d ops\n\t%d ns\n\t%d ops/s\n", handleDequeueing.n, spentTime, handleDequeueing.n * 1000_000_000L / spentTime);
        System.out.println(result);
        handleDequeueing.finish.countDown();

        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result);
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

    private Behavior<Command> onHandleEnqueueing(HandleEnqueueing handleEnqueueing) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);
        ActorRef<BlockableCountActor.Command> actor = getContext().spawnAnonymous(BlockableCountActor.create(startLatch, finishLatch, handleEnqueueing.n));

        long spentTime = timed((notUsed) -> {
            BlockableCountActor.EmptyMessage message = new BlockableCountActor.EmptyMessage();
            for (int i = 0; i < handleEnqueueing.n; i++) {
                actor.tell(message);
            }
            return null;
        });

        startLatch.countDown();
        finishLatch.await();

//        System.out.println("Enqueueing:");
//        System.out.printf("\t%d ops\n", handleEnqueueing.n);
//        System.out.printf("\t%d ns\n", spentTime);
//        System.out.printf("\t%d ops/s\n", handleEnqueueing.n * 1000_000_000L / spentTime);
//        handleEnqueueing.finish.countDown();

        String result = String.format("Enqueueing:\n\t%d ops\n\t%d ns\n\t%d ops/s\n", handleEnqueueing.n, spentTime, handleEnqueueing.n * 1000_000_000L / spentTime);
        System.out.println(result);
        handleEnqueueing.finish.countDown();

        try {
            File file =new File("out.txt");
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWritter = new FileWriter(file.getName(),true);
            BufferedWriter bufferWritter = new BufferedWriter(fileWritter);
            bufferWritter.write(result);
            bufferWritter.close();
        } catch (IOException e) {
            logger.error(e.toString());
        }
        return this;
    }

}
