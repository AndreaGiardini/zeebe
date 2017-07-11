package io.zeebe.perftest.helper;

import static io.zeebe.client.ClientProperties.CLIENT_MAXREQUESTS;
import static io.zeebe.perftest.helper.TestHelper.printProperties;

import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.zeebe.client.ClientProperties;
import io.zeebe.client.ZeebeClient;
import io.zeebe.perftest.CommonProperties;
import io.zeebe.perftest.reporter.FileReportWriter;
import io.zeebe.perftest.reporter.RateReporter;

public abstract class MaxRateThroughputTest
{
    public static final String TEST_WARMUP_TIMEMS = "test.warmup.timems";
    public static final String TEST_WARMUP_REQUESTRATE = "test.warmup.requestRate";
    public static final String TEST_MAX_CONCURRENT_REQUESTS = "128";

    public void run()
    {
        final Properties properties = System.getProperties();

        setDefaultProperties(properties);
        ClientProperties.setDefaults(properties);

        printProperties(properties);

        ZeebeClient client = null;

        try
        {
            client = ZeebeClient.create(properties);
            client.connect();

            executeSetup(properties, client);
            executeWarmup(properties, client);
            executeTest(properties, client);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            client.close();
        }

    }

    protected void setDefaultProperties(final Properties properties)
    {
        properties.putIfAbsent(TEST_WARMUP_TIMEMS, "30000");
        properties.putIfAbsent(TEST_WARMUP_REQUESTRATE, "1000");
        properties.putIfAbsent(CommonProperties.TEST_TIMEMS, "30000");
        properties.putIfAbsent(TEST_MAX_CONCURRENT_REQUESTS, "128");
        properties.putIfAbsent(CommonProperties.TEST_OUTPUT_FILE_NAME, "data/output.txt");
        properties.putIfAbsent(CLIENT_MAXREQUESTS, "256");
    }

    protected void executeSetup(Properties properties, ZeebeClient client)
    {
        // noop
    }

    @SuppressWarnings("rawtypes")
    protected void executeWarmup(Properties properties, ZeebeClient client)
    {
        System.out.format("Executing warmup\n");

        final int warmupRequestRate = Integer.parseInt(properties.getProperty(TEST_WARMUP_REQUESTRATE));
        final int warmupTimeMs = Integer.parseInt(properties.getProperty(TEST_WARMUP_TIMEMS));

        final Consumer<Long> noopLatencyConsumer = (latency) ->
        {
            // ignore
        };

        final Supplier<Future> requestFn = requestFn(client);

        TestHelper.executeAtFixedRate(requestFn, noopLatencyConsumer, warmupRequestRate, warmupTimeMs);

        System.out.println("Finished warmup.");

        TestHelper.gc();
    }

    @SuppressWarnings("rawtypes")
    protected void executeTest(Properties properties, ZeebeClient client)
    {
        System.out.format("Executing test\n");

        final int testTimeMs = Integer.parseInt(properties.getProperty(CommonProperties.TEST_TIMEMS));
        final int maxConcurrentRequests = Integer.parseInt(properties.getProperty(TEST_MAX_CONCURRENT_REQUESTS));
        final String outputFileName = properties.getProperty(CommonProperties.TEST_OUTPUT_FILE_NAME);

        final FileReportWriter fileReportWriter = new FileReportWriter();
        final RateReporter rateReporter = new RateReporter(1, TimeUnit.SECONDS, fileReportWriter);

        new Thread()
        {
            @Override
            public void run()
            {
                rateReporter.doReport();
            }

        }.start();

        final Supplier<Future> requestFn = requestFn(client);

        TestHelper.executeAtMaxRate(requestFn, rateReporter, testTimeMs, maxConcurrentRequests);

        System.out.format("Finished test.\n");

        rateReporter.exit();

        fileReportWriter.writeToFile(outputFileName);

        TestHelper.gc();
    }

    protected abstract Supplier<Future> requestFn(ZeebeClient client);
}

