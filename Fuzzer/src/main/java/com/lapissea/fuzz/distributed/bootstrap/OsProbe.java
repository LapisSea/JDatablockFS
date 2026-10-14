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
	
	private record ProbeResult(int exit, String command, String output){
		
		Optional<String> stdout(){ return exit == 0? Optional.of(output.trim()) : Optional.empty(); }
		
		String describe(){
			return command + " exited " + exit + (output.isBlank()? "" : " with \"" + firstLine(output) + "\"");
		}
	}
	
	record Answer(HostOs os, String evidence4K){ }
	
	/**
	 * @throws IOException when neither probe decides it, with how to set {@code os} explicitly
	 */
	static Answer detect(RemoteConfig.Host host) throws IOException, InterruptedException{
		ProbeResult uname = probe(host, "uname -s");
		ProbeResult ver   = uname.stdout().filter(OsProbe::isPosix).isPresent()? null : probe(host, "cmd /c ver");
		
		var answer = decide(uname.stdout(), ver == null? Optional.empty() : ver.stdout());
		if(answer.isEmpty()){
			if(uname.output.contains("Permission denied")){
				throw new IOException(SshWorkerLauncher.willNotConnect(
					host,
					"Could not authenticate without a password!\n    " +
					uname.describe() +
					(ver == null? "" : "\n    " + ver.describe())
				));
			}
			throw new IOException(SshWorkerLauncher.willNotConnect(
				host,
				"Operating system could not be detected over ssh:\n    " +
				uname.describe() +
				(ver == null? "" : "\n    " + ver.describe())
			));
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
	
	private static ProbeResult probe(RemoteConfig.Host host, String command) throws IOException, InterruptedException{
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
				throw new IOException(SshWorkerLauncher.willNotConnect(
					host,
					"it did not answer the operating system probe (" + command + ") within " + timeout + "s")
				);
			}
			reader.join(TimeUnit.SECONDS.toMillis(5));
			return new ProbeResult(process.exitValue(), command, collected.toString(UTF_8));
		}finally{
			if(process.isAlive()){ process.destroyForcibly(); }
		}
	}
	
	private static String firstLine(String text){
		var line = text.strip();
		int end  = line.indexOf('\n');
		return end<0? line : line.substring(0, end);
	}
}
