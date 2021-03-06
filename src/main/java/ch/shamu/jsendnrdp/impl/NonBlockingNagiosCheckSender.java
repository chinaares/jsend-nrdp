package ch.shamu.jsendnrdp.impl;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.RateLimiter;

import ch.shamu.jsendnrdp.NRDPException;
import ch.shamu.jsendnrdp.NRDPServerConnectionSettings;
import ch.shamu.jsendnrdp.NagiosCheckSender;
import ch.shamu.jsendnrdp.domain.NagiosCheckResult;

/**
 * This implementation uses a bounded queue of alerts to send (jobs).<br>
 * A ThreadPoolExecutor processes those jobs.<br>
 * Due to the asynchronous nature of this sender, the only exception that can be thrown by the "send"<br>
 * method is if the maxQueueSize is reached (IOException). All exception which can occur during job execution are logged.<br>
 * This implementation features a configurable level of concurrency and throttling of job executions.<br>
 * This allows to protect the remote nagios server if an application tries to send too many check results too fast.<br>
 */
public class NonBlockingNagiosCheckSender implements NagiosCheckSender {

	private final static Logger logger = LoggerFactory.getLogger(NonBlockingNagiosCheckSender.class);

	private final int maxQueueSize;
	private final ThreadPoolExecutor executor;
	private final NagiosCheckSender sender;
	private final RateLimiter rateLimiter;

	/**
	 * Bean that knows how to send nagios alerts in a non blocking way, has configurable concurrency level and supports throttling
	 *
	 * @param server       is the nrdp server connection settings
	 * @param nbThreads    is the number of worker threads for sending nagios alerts (concurrency level)
	 * @param maxQueueSize is the maximum number of queued jobs before starting rejecting new job requests (IOException) (0 means go on until
	 *                     OutOfMemory) jobs currently in execution are not taken into account when computing queue size.
	 * Note that this version does not throttle job executions.
	 */
	public NonBlockingNagiosCheckSender(NRDPServerConnectionSettings server, int nbThreads, int maxQueueSize) {
		this(server, nbThreads, maxQueueSize, 0d);
	}

	/**
	 * Bean that knows how to send nagios alerts in a non blocking way, has configurable concurrency level and supports throttling
	 *
	 * @param server                is the nrdp server connection settings
	 * @param nbThreads             is the number of worker threads for sending nagios alerts (concurrency level)
	 * @param maxQueueSize          is the maximum number of queued jobs before starting rejecting new job requests (IOException) (0 means queue jobs until
	 *                              OutOfMemory, please don't...) jobs currently in execution are not taken into account when computing queue size.
	 * @param maxRequestsPerSeconds throttling of requests sent to the server, it's the maximum number of requests send to the server per second (0 means
	 *                              unlimited). The jobs currently in execution will block in order to respect this rate.
	 */
	public NonBlockingNagiosCheckSender(NRDPServerConnectionSettings server, int nbThreads, int maxQueueSize, double maxRequestsPerSeconds) {

		this(server, maxQueueSize, maxRequestsPerSeconds,
				new ScheduledThreadPoolExecutor(nbThreads, new ThreadFactory() {
					private int count = 0;

					public Thread newThread(Runnable r) {
						// pretty naming of threads
						return new Thread(r, "nrdp-sender" + "-" + count++);
					}
				}));

	}

	/**
	 * Bean that knows how to send nagios alerts in a non blocking way, has configurable concurrency level and supports throttling
	 *
	 * @param server                is the nrdp server connection settings
	 * @param maxQueueSize          is the maximum number of queued jobs before starting rejecting new job requests (IOException) (0 means queue jobs until
	 *                              OutOfMemory, please don't...) jobs currently in execution are not taken into account when computing queue size.
	 * @param maxRequestsPerSeconds throttling of requests sent to the server, it's the maximum number of requests send to the server per second (0 means
	 *                              unlimited). The jobs currently in execution will block in order to respect this rate.
	 * @param executor              executor to use for sending the checks
	 */
	public NonBlockingNagiosCheckSender(NRDPServerConnectionSettings server, int maxQueueSize,
			double maxRequestsPerSeconds, ThreadPoolExecutor executor) {

		this.sender = new NagiosCheckSenderImpl(server);

		this.executor = executor;

		if (maxRequestsPerSeconds == 0d) {
			this.rateLimiter = RateLimiter.create(Double.MAX_VALUE); // FIRE AT WILL !
		} else {
			this.rateLimiter = RateLimiter.create(maxRequestsPerSeconds);
		}

		this.maxQueueSize = maxQueueSize;
	}

	/**
	 * Send the check results asynchronously, and return a future.
	 * The <code>send()</code> method will also send the results asynchronously,
	 * but doesn't return a Future object that can be used to manipulate or
	 * query the execution of the actual sending process
	 */
	public Future sendAsync(Collection<NagiosCheckResult> checkResults) throws NRDPException, IOException {
		// deal with binding of the queue
		if (maxQueueSize > 0 && executor.getQueue().size() >= maxQueueSize) {
			throw new IOException("Nagios check result could not be submitted : maximum number of queued results to send reached ("
					+ maxQueueSize + ")");
		}
		return executor.submit(new NonBlockingSender(checkResults, sender, rateLimiter));
	}

	public void send(Collection<NagiosCheckResult> checkResults) throws NRDPException, IOException {
		sendAsync(checkResults);
	}

	/**
	 * Shuts down the underlying executor. No new results should be sent
	 * through this sender after this method is invoked.
	 */
	public void shutdown() {
		executor.shutdown();
	}

}
