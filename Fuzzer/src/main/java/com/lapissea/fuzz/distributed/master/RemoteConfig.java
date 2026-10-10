package com.lapissea.fuzz.distributed.master;

import com.google.gson.Gson;
import com.lapissea.fuzz.distributed.bootstrap.HostOs;
import com.lapissea.fuzz.distributed.bootstrap.SshWorkerLauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public record RemoteConfig(List<Host> hosts){
	
	public record Host(
		String name, String sshHost,
		String javaExecutable, String cacheDirectory,
		int parallelism, int connectTimeoutSeconds,
		int tunnelPort, String workDirectory,
		String os
	){
		public Host{
			if(sshHost == null || !sshHost.matches("[A-Za-z0-9_@.:-]+") || sshHost.startsWith("-")){
				throw new IllegalArgumentException("Invalid SSH host");
			}
			if(name == null || name.isBlank()) name = sshHost;
			if(javaExecutable == null || javaExecutable.isBlank()) javaExecutable = "java";
			if(cacheDirectory == null || cacheDirectory.isBlank()) cacheDirectory = ".cache/jdatablockfs/fuzzer";
			if(parallelism<0) throw new IllegalArgumentException("Parallelism must be zero (automatic) or positive");
			if(connectTimeoutSeconds<=0) connectTimeoutSeconds = 10;
			if(tunnelPort != 0 && (tunnelPort<1024 || tunnelPort>65535)){
				throw new IllegalArgumentException("Invalid tunnel port");
			}
			if(os != null && !os.isBlank()) HostOs.parse(os); // Validate eagerly: an unsupported value must fail at config load, not at first ssh.
			// These values cross the helper's line-based protocols (stdin manifests, the Windows spec
			// file); a newline would be silently misread as two entries.
			noNewlines("name", name);
			noNewlines("javaExecutable", javaExecutable);
			noNewlines("cacheDirectory", cacheDirectory);
			noNewlines("workDirectory", workDirectory);
		}
		private static void noNewlines(String field, String value){
			if(value != null && (value.indexOf('\n')>=0 || value.indexOf('\r')>=0)){
				throw new IllegalArgumentException(field + " must not contain newlines");
			}
		}
		
		/**
		 * The operating system declared for this host, or empty when it is unset — an unset {@code os}
		 * is detected over ssh before the helper is contacted for real, see
		 * {@link SshWorkerLauncher}. A declared value is trusted, but cross-checked against what the
		 * helper reports during preparation.
		 */
		public Optional<HostOs> declaredOs(){
			return Optional.ofNullable(os).filter(value -> !value.isBlank()).map(HostOs::parse);
		}
	}
	
	public RemoteConfig{
		hosts = hosts == null? List.of() : List.copyOf(hosts);
		var names = new HashSet<String>();
		names.add("local");
		for(var host : hosts){
			if(!names.add(host.name())){
				throw new IllegalArgumentException("Duplicate or reserved helper name: " + host.name());
			}
		}
	}
	
	public static Path configurationPath(){
		var override = System.getProperty("fuzz.remote.config");
		if(override != null){
			return Path.of(override);
		}
		var xdg = System.getenv("XDG_CONFIG_HOME");
		if(xdg == null || xdg.isBlank()){
			return Path.of(System.getProperty("user.home"), ".config").resolve("jdatablockfs/fuzzer.json");
		}
		return Path.of(xdg).resolve("jdatablockfs/fuzzer.json");
	}
	
	public record ConfigResult(RemoteConfig config, Path path){ }
	
	public static ConfigResult load() throws IOException{
		var path = configurationPath();
		if(!Files.exists(path)){
			System.out.println("[fuzz] Info: No fuzz helpers configured at: " + path.toAbsolutePath());
			return new ConfigResult(new RemoteConfig(List.of()), path);
		}
		
		try(var reader = Files.newBufferedReader(path)){
			var result = new Gson().fromJson(reader, RemoteConfig.class);
			if(result == null){
				throw new IOException("Empty helper configuration: " + path);
			}
			return new ConfigResult(result, path);
		}catch(RuntimeException e){
			throw new IOException("Invalid helper configuration: " + path, e);
		}
	}
}
