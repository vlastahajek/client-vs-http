package io.bonitoo.client_vs_http;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.cli.CommandLine;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * @author Jakub Bednar (26/09/2019 12:21)
 */
@SuppressWarnings("CatchMayIgnoreException")
abstract class AbstractIOTWriter {

    final String measurementName;
    private final int threadsCount;
    private final int secondsCount;
    private final int lineProtocolsCount;
    private final int expectedCount;
    private volatile boolean execute = true;
    private boolean skipCount;

    AbstractIOTWriter(CommandLine line) {

        measurementName = line.getOptionValue("measurementName", "sensor_" + System.currentTimeMillis());
        threadsCount = Integer.parseInt(line.getOptionValue("threadsCount", "2000"));
        secondsCount = Integer.parseInt(line.getOptionValue("secondsCount", "30"));
        lineProtocolsCount = Integer.parseInt(line.getOptionValue("lineProtocolsCount", "100"));
        skipCount = line.hasOption("skipCount");
        expectedCount = threadsCount * secondsCount * lineProtocolsCount;

        System.out.println("measurement:        " + measurementName);
        System.out.println("threadsCount:       " + threadsCount);
        System.out.println("secondsCount:       " + secondsCount);
        System.out.println("lineProtocolsCount: " + lineProtocolsCount);
        System.out.println();
    }

    AbstractIOTWriter start() {

        System.out.println("expected size: " + expectedCount);
        System.out.println();

        ExecutorService executor = Executors.newFixedThreadPool(threadsCount);

        for (int i = 1; i < threadsCount + 1; i++) {
            Runnable worker = new IOTSensor(i, measurementName, secondsCount, lineProtocolsCount);
            executor.execute(worker);
        }
        executor.shutdown();

        long start = System.currentTimeMillis();

        // Wait until all threads are finish
        while (!executor.isTerminated()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }

            //
            // Stop benchmark after elapsed time
            //
            if (System.currentTimeMillis() - start > secondsCount * 1_000 && execute) {
                System.out.println("\n\nThe time: " + secondsCount + " seconds elapsed! Stopping all writers");
                execute = false;
                executor.shutdownNow();
            }
        }

        System.out.println();
        System.out.println();

        finished();

        return this;
    }

    void verify() {

        if (skipCount) {
            return;
        }

        Double count = countInDB();

        System.out.println("Results:");
        System.out.println("-> expected:        " + expectedCount);
        System.out.println("-> total:           " + count);
        System.out.println("-> rate [%]:        " + (count / expectedCount) * 100);
        System.out.println("-> rate [msg/sec]:  " + ansi().fgGreen().a((count / secondsCount)).reset());
    }

    abstract void writeRecord(final String records) throws Exception;

    abstract Double countInDB();

    abstract void finished();

    private class IOTSensor implements Runnable {

        private final Integer id;
        private final String measurementName;
        private final int secondCount;
        private final int lineProtocolsCount;

        IOTSensor(Integer id, String measurementName, final int secondCount, final int lineProtocolsCount) {
            this.id = id;
            this.measurementName = measurementName;
            this.secondCount = secondCount;
            this.lineProtocolsCount = lineProtocolsCount;
        }

        @Override
        public void run() {

            try {
                doLoad();
            } catch (Exception e) {
                if (e instanceof InterruptedException || e instanceof InterruptedIOException) {
                    return;
                }
                System.out.println("e.getMessage() = " + e.getMessage());
            }
        }

        private void doLoad() throws Exception {
            for (int i = 0; i < secondCount && execute; i++) {

                if (!execute) {
                    break;
                }

                //
                // Logging
                //
                if (id == 1) {
                    System.out.print(String.format("\rwriting iterations: %s/%s ", i + 1, secondCount));
                }

                //
                // Generate data
                //
                int start = i * lineProtocolsCount;
                int end = start + lineProtocolsCount;

                List<String> records = IntStream
                        .range(start, end)
                        .mapToObj(j -> String.format("%s,id=%s temperature=%d %d", measurementName, id, System.currentTimeMillis(), j))
                        .collect(Collectors.toList());

                //
                // Write records one by one
                //
                for (String record : records) {
                    if (execute) {
                        writeRecord(record);
                    }
                }

                if (!execute) {
                    break;
                }

                Thread.sleep(1000);
            }
        }
    }
}
