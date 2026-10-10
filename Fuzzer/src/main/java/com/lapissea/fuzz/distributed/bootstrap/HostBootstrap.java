package com.lapissea.fuzz.distributed.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

interface HostBootstrap{
	
	/**
	 * Reserve the cache for this session and probe which artifacts are missing.
	 *
	 * @param artifactNames the bundle artifact names in order. The missing indexes in the response refer to this list
	 */
	Script prepare(String cache, String parent, UUID session, List<String> artifactNames);
	
	/**
	 * Publish a ustar stream (written by the caller to the process stdin) into the cache.
	 *
	 * @param artifactNames the names in the archive. An implementation may take them from the
	 *                      extracted tree instead, since it only ever publishes what it extracted itself
	 */
	Script upload(String cache, UUID session, List<String> artifactNames);
	
	/**
	 * Cleans up this session's {@link #prepare} and {@link #upload} after a failed launch.
	 */
	Script failed(String cache, UUID session);
	
	/**
	 * @param timeoutMillis zero tries once, positive waits that long
	 * @return the held lock, or null when a zero timeout finds it already held
	 * @throws IOException when a positive timeout expires without acquiring it
	 */
	CacheGate acquireCacheGate(Path cache, long timeoutMillis) throws IOException;
	
	/**
	 * The remote command that runs {@code executableAndArgs} (executable first).
	 *
	 * @param session           scopes any staging the implementation creates, so {@link #failed} can clean it up
	 * @param executableAndArgs the executable followed by its literal arguments
	 * @param transfer          ships the arguments when the command line cannot carry them
	 */
	String remoteInvocation(UUID session, List<String> executableAndArgs, PayloadTransfer transfer) throws IOException, InterruptedException;
	
	record Script(String command, List<String> stdinLines){ }
	
	/**
	 * Delivers {@code payload} to the helper machine via {@code command}, for transport that cannot express it.
	 */
	interface PayloadTransfer{
		void send(String command, byte[] payload) throws IOException, InterruptedException;
	}
}
