package net.shipilev.concurrent.torture;

import net.shipilev.concurrent.torture.util.Multiset;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Basic runner for concurrency tests.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class Runner {
    private final PrintWriter pw;
    private final PrintWriter xml;
    private final int time;
    private final int loops;
    private final ExecutorService pool;
    private final boolean shouldYield;
    private volatile boolean isStopped;
    private final int wtime;
    private final int witers;

    public Runner(Options opts) throws FileNotFoundException {
        this.pw = new PrintWriter(System.out, true);
        this.xml = new PrintWriter(opts.getResultFile());
        time = opts.getTime();
        loops = opts.getLoops();
        wtime = opts.getWarmupTime();
        witers = opts.getWarmupIterations();
        shouldYield = opts.shouldYield();
        pool = Executors.newCachedThreadPool();
    }

    public void ensureThreads(int threads) {
        if (Runtime.getRuntime().availableProcessors() < threads && !shouldYield) {
            pw.println("WARNING: This test should be run with at least " + threads + " CPUs to get reliable results, or enable yielding");
        }
    }

    /**
     * Run the test.
     * This method blocks until test is complete
     *
     * @param test test to run
     * @param <S> test state object type
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public <S> void run(OneActorOneObserverTest<S> test) throws ExecutionException, InterruptedException {
        pw.println("Running " + test.getClass().getName());
        ensureThreads(3);

        pw.print("Warmup ");
        for (int c = 0; c < witers; c++) {
            pw.print(".");
            pw.flush();
            run(test, wtime, true);
        }
        pw.println();

        run(test, time, false);
    }

    private <S> void run(final OneActorOneObserverTest<S> test, int time, boolean dryRun) throws InterruptedException, ExecutionException {
        final SingleSharedStateHolder<S> holder = new SingleSharedStateHolder<S>();

        // current should be null so that injector could inject the first instance
        holder.current = null;

        isStopped = false;

        /*
           Injector thread: injects new states until interrupted.
         */
        Future<?> s1 = pool.submit(new Runnable() {
            public void run() {
                while (!isStopped) {
                    S[] newStride = (S[]) new Object[loops];
                    for (int c = 0; c < loops; c++) {
                        newStride[c] = test.newState();
                    }

                    while (holder.current != null) {
                        if (isStopped) {
                            return;
                        }
                        if (shouldYield) Thread.yield();
                    }
                    holder.current = newStride;
                }
            }
        });

        /*
           Actor 1 thread.
           The rationale for its loop is as follows:
              a. We should be easy on checking the interrupted status, hence we do $LOOPS internally
              b. Thread should not observe the state object more than once
         */
        Future<?> a1 = pool.submit(new Runnable() {
            public void run() {
                S[] last = null;

                while (!isStopped) {
                    S[] cur = holder.current;
                    if (cur != null && last != cur) {
                        for (int l = 0; l < loops; l++) {
                            test.actor1(cur[l]);
                        }
                        last = cur;
                    } else {
                        if (shouldYield) Thread.yield();
                    }
                }
            }
        });

        /*
          Observer thread.
          The rationale for its loop is as follows:
              a. We should be easy on checking the interrupted status, hence we do $LOOPS internally
              b. Thread should not observe the state object more than once
              c. The overhead of doing the work inside the inner loop should be small
              d. $state is getting reused, so we end up marshalling it to long to count properly
        */
        Future<Multiset<Long>> res = pool.submit(new Callable<Multiset<Long>>() {
            public Multiset<Long> call() {
                Multiset<Long> set = new Multiset<Long>();

                S[] last = null;
                byte[] state = new byte[8];
                byte[][] results = new byte[loops][];

                while (!isStopped) {
                    S[] cur = holder.current;

                    if (cur != null && last != cur) {
                        for (int l = 0; l < loops; l++) {
                            test.observe(cur[l], state);
                            results[l] = new byte[8];
                            System.arraycopy(state, 0, results[l], 0, 8);
                        }

                        last = cur;

                        for (int i = 0; i < loops; i++) {
                            set.add(byteArrToLong(results[i]));
                        }

                        // let others proceed
                        holder.current = null;
                    } else {
                        if (shouldYield) Thread.yield();
                    }
                }
                return set;
            }
        });

        TimeUnit.MILLISECONDS.sleep(time);

        isStopped = true;
        a1.get();
        s1.get();
        res.get();

        if (!dryRun) {
            dump(test, res.get());
            judge(test, res.get());
        }
    }

    public <S> void run(final TwoActorsOneArbiterTest<S> test) throws InterruptedException, ExecutionException {
        pw.println("Running " + test.getClass().getName());
        ensureThreads(4);

        pw.print("Warmup ");
        for (int c = 0; c < witers; c++) {
            pw.print(".");
            pw.flush();
            run(test, wtime, true);
        }
        pw.println();

        run(test, time, false);
    }

    public <S> void run(final TwoActorsOneArbiterTest<S> test, int time, boolean dryRun) throws InterruptedException, ExecutionException {
        final TwoSharedStateHolder<S> holder = new TwoSharedStateHolder<S>();

        // need to initialize so that actor thread will not NPE.
        // once injector catches up, it will push fresh state objects
        holder.current = test.newState();

        isStopped = false;

         /*
           Injector thread: injects new states until interrupted.
           There are an additional constraints:
              a. If actors results are not yet consumed, do not push the new state.
                 This will effectively block actors from working until arbiter consumes their result.
         */
        Future<?> s1 = pool.submit(new Runnable() {
            public void run() {
                while (!isStopped) {
                    while (holder.t1 != null && holder.t2 != null && !isStopped)
                        if (shouldYield) Thread.yield();
                    holder.current = test.newState();
                }
            }
        });

        /*
           Actor 1 thread.
           The rationale for its loop is as follows:
              a. We should be easy on checking the interrupted status, hence we do $LOOPS internally
              b. Thread should not observe the state object more than once
              c. Once thread is done with its work, it publishes the reference to state object for arbiter
         */
        Future<?> a1 = pool.submit(new Runnable() {
            public void run() {
                S last = null;
                while (!isStopped) {
                    int l = 0;
                    while (l < loops) {
                        S cur = holder.current;
                        if (last != cur) {
                            test.actor1(cur);
                            holder.t1 = cur;
                            last = cur;
                        } else {
                            if (shouldYield) Thread.yield();
                        }
                        l++;
                    }
                }
            }
        });

        /*
           Actor 2 thread.
           The rationale for its loop is as follows:
              a. We should be easy on checking the interrupted status, hence we do $LOOPS internally
              b. Thread should not observe the state object more than once
              c. Once thread is done with its work, it publishes the reference to state object for arbiter
         */
        Future<?> a2 = pool.submit(new Runnable() {
            public void run() {
                S last = null;
                while (!isStopped) {
                    int l = 0;
                    while (l < loops) {
                        S cur = holder.current;
                        if (last != cur) {
                            test.actor2(cur);
                            last = cur;
                            holder.t2 = cur;
                        } else {
                            if (shouldYield) Thread.yield();
                        }
                        l++;
                    }
                }
            }
        });

        /*
          Arbiter thread.
          The rationale for its loop is as follows:
              a. We should be easy on checking the interrupted status, hence we do $LOOPS internally
              b. Thread should not observe the state object more than once
              c. The overhead of doing the work inside the inner loop should be small
              d. $state is getting reused, so we end up marshalling it to long to count properly
              e. Arbiter waits until both actors have finished their work and published their results
        */
        Future<Multiset<Long>> res = pool.submit(new Callable<Multiset<Long>>() {
            public Multiset<Long> call() {
                byte[] res = new byte[8];

                Multiset<Long> set = new Multiset<Long>();

                byte[][] results = new byte[loops][];
                while (!isStopped) {
                    int c = 0;
                    int l = 0;
                    while (l < loops) {
                        S s1 = holder.t1;
                        S s2 = holder.t2;
                        if (s1 == s2 && s1 != null) {
                            test.arbitrate(s1, res);
                            results[c] = new byte[8];
                            System.arraycopy(res, 0, results[c], 0, 8);
                            c++;
                            holder.t1 = null;
                            holder.t2 = null;
                        } else {
                            if (shouldYield) Thread.yield();
                        }
                        l++;
                    }

                    for (int i = 0; i < c; i++) {
                        set.add(byteArrToLong(results[i]));
                    }
                }
                return set;
            }
        });

        TimeUnit.MILLISECONDS.sleep(time);

        isStopped = true;
        s1.get();
        a1.get();
        a2.get();
        res.get();

        if (!dryRun) {
            dump(test, res.get());
            judge(test, res.get());
        }
    }

    private void dump(ConcurrencyTest test, Multiset<Long> results) {
        xml.println("<result>");
        xml.println("<test>" + test.getClass().getName() + "</test>");
        xml.println("<states>");
        for (Long e : results.keys()) {
            byte[] b = longToByteArr(e);
            byte[] t = new byte[test.resultSize()];
            System.arraycopy(b, 0, t, 0, test.resultSize());
            b = t;

            xml.println("<state>");
            xml.println("<id>" + Arrays.toString(b) + "</id>");
            xml.println("<count>" + results.count(e) + "</count>");
            xml.println("</state>");
        }
        xml.println("</states>");
        xml.println("</result>");
    }

    private void judge(ConcurrencyTest test, Multiset<Long> results) {
        pw.printf("%35s %12s %-20s\n", "Observed state", "Occurrences", "Interpretation");
        for (Long e : results.keys()) {

            byte[] b = longToByteArr(e);

            byte[] t = new byte[test.resultSize()];
            System.arraycopy(b, 0, t, 0, test.resultSize());
            b = t;

            boolean isFailed;
            switch (test.test(b)) {
                case ACCEPTABLE:
                case TRANSIENT:
                    // no implementation yet
                    isFailed = false;
                    break;

                case EXPECTED:
                    isFailed = (results.count(e) == 0);
                    break;

                case NOT_EXPECTED:
                    isFailed = (results.count(e) > 0);
                    break;
                default:
                    throw new IllegalStateException();
            }

            pw.printf("%35s (%10d) %6s %-40s\n", Arrays.toString(b), results.count(e), (isFailed ? "ERROR:" : "OK:"), test.test(b));
        }

        pw.println();
    }


    private byte[] longToByteArr(Long element) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(element);
        return buf.array();
    }

    public static long byteArrToLong(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b);
        return buf.getLong();
    }

    public void close() {
        pool.shutdownNow();
        xml.close();
    }

    public static class SingleSharedStateHolder<S> {
        volatile S[] current;
    }

    public static class TwoSharedStateHolder<S> {
        volatile S current;
        volatile S t1;
        volatile S t2;
    }

}
