package ch.shamu.jsendnrdp.impl;

import java.io.IOException;

import java.util.Collection;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.RateLimiter;

import ch.shamu.jsendnrdp.NagiosCheckSender;
import ch.shamu.jsendnrdp.NRDPException;
import ch.shamu.jsendnrdp.domain.NagiosCheckResult;

/**
 * Instances of this class are used to send the check results
 * by a non-blocking threads. The caller may get access to these
 * instances when using a custom executor.
 */
public class NonBlockingSender implements Callable<Collection<NagiosCheckResult>> {

	private final static Logger logger = LoggerFactory.getLogger(NonBlockingSender.class);

	private final Collection<NagiosCheckResult> results;
	private final NagiosCheckSender sender;
	private final RateLimiter rateLimiter;

	public NonBlockingSender(Collection<NagiosCheckResult> results, NagiosCheckSender sender, RateLimiter rateLimiter) {
		this.results = results;
		this.sender = sender;
		this.rateLimiter = rateLimiter;
	}

	public Collection<NagiosCheckResult> call() {
		try {
			double waitTime = rateLimiter.acquire(); // Eventually wait because of throttling
			if (waitTime > 0) {
				logger.debug("job throttling wait : {}", waitTime);
			}
			sender.send(results);
                        return results;
		}
		catch (Throwable e) {
			logger.error("Problem sending nagios check result to NRDP server: ", e);
                        if (e instanceof RuntimeException) {
                            throw (RuntimeException)e;
                        } else if (e instanceof Error) {
                            throw (Error)e;
                        } else {
                            throw new RuntimeException(e);
                        }
		}
	}

	public Collection<NagiosCheckResult> getResults() {
		return results;
	}

}

