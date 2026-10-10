package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.master.RemoteConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Detects a helper's OS over ssh when the config leaves {@code os} unset
 */
final class OsProbe{
	
	private static final int PROBE_GRACE_SECONDS = 15;
	
	private OsProbe(){ }
	
	record Answer(HostOs os, String evidence4K){ }
	
	/**
	 * @throws IOException when neither probe decides it, with how to set {@code os} explicitly
	 */
	static Answer detect(RemoteConfig.Host host) throws IOException, InterruptedException{
		var uname = probe(host, "uname -s");
		// A Linux answer settles it on its own, so the Windows probe only runs while POSIX is unproven.
		var ver = uname.stdout().filter(OsProbe::isPosix).isPresent()? null : probe(host, "cmd /c ver");
		
		var answer = decide(uname.stdout(), ver == null? Optional.empty() : ver.stdout());
		if(answer.isEmpty()){
			throw new IOException(SshWorkerLauncher.willNotConnect(host,
			                                                       "its operating system could not be detected over ssh (" + uname.describe("uname -s") +
			                                                       (ver == null? "" : ", " + ver.describe("cmd /c ver")) +
			                                                       "), and only Linux and Windows helpers are supported. Set \"os\" to \"linux\" or to \"windows\" for it in " +
			                                                       RemoteConfig.configurationPath()));
		}
		return answer.get();
	}
	
	static Optional<Answer> decide(Optional<String> uname, Optional<String> ver){
		if(uname.filter(OsProbe::isPosix).isPresent()){
			return Optional.of(new Answer(HostOs.LINUX, "uname -s = Linux"));
		}
		if(ver.filter(OsProbe::isWindowsVersion).isPresent()){
			return Optional.of(new Answer(HostOs.WINDOWS, "cmd /c ver = " + firstLine(ver.get())));
		}
		return Optional.empty();
	}
	
	private static boolean isPosix(String unameOutput){
		return unameOutput.lines().anyMatch(line -> line.trim().equalsIgnoreCase("Linux"));
	}
	private static boolean isWindowsVersion(String verOutput){
		var lower = verOutput.toLowerCase();
		return lower.contains("windows") && lower.contains("[version");
	}
	
	/**
	 * An unreachable probe is an error, not a negative answer, so a network hiccup is never mistaken for the other OS.
	 */
	private static Result probe(RemoteConfig.Host host, String command) throws IOException, InterruptedException{
		var timeout   = host.connectTimeoutSeconds() + PROBE_GRACE_SECONDS;
		var process   = new ProcessBuilder(SshWorkerLauncher.ssh(host, command)).redirectErrorStream(true).start();
		var collected = new ByteArrayOutputStream();
		var reader = Thread.ofVirtual().name("fuzz-os-probe-" + host.name()).start(() -> {
			try(InputStream input = process.getInputStream()){
				input.transferTo(collected);
			}catch(IOException ignored){
				// The wait and the exit code below are what report a broken probe.
			}
		});
		try{
			if(!process.waitFor(timeout, TimeUnit.SECONDS)){
				throw new IOException(SshWorkerLauncher.willNotConnect(host,
				                                                       "it did not answer the operating system probe (" + command + ") within " + timeout + "s"));
			}
			reader.join(TimeUnit.SECONDS.toMillis(5));
			return new Result(process.exitValue(), collected.toString(UTF_8));
		}finally{
			if(process.isAlive()){ process.destroyForcibly(); }
		}
	}
	
	private record Result(int exit, String output){
		
		Optional<String> stdout(){ return exit == 0? Optional.of(output.trim()) : Optional.empty(); }
		
		String describe(String command){
			return command + " exited " + exit + (output.isBlank()? "" : " with \"" + firstLine(output) + "\"");
		}
	}
	
	private static String firstLine(String text){
		var line = text.strip();
		int end  = line.indexOf('\n');
		return end<0? line : line.substring(0, end);
	}
}
