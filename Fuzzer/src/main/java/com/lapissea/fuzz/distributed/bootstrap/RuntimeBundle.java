package com.lapissea.fuzz.distributed.bootstrap;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RuntimeBundle implements AutoCloseable{
	public record Artifact(String name, Path file){ }
	
	private final SnapshotLease  lease;
	private final List<Artifact> artifacts;
	private final List<String>   classPath;
	private final List<String>   modulePath;
	private final String         id;
	private final List<String>   jvmArguments;
	
	private RuntimeBundle(
		List<Artifact> artifacts, List<String> cp, List<String> mp, String id, List<String> jvmArguments, SnapshotLease lease
	){
		this.lease = lease;
		this.jvmArguments = List.copyOf(jvmArguments);
		this.artifacts = List.copyOf(artifacts);
		classPath = List.copyOf(cp);
		modulePath = List.copyOf(mp);
		this.id = id;
	}
	
	public List<String> jvmArguments(){ return jvmArguments; }
	public String id()                { return id; }
	public List<Artifact> artifacts() { return artifacts; }
	public List<String> classPath()   { return classPath; }
	public List<String> modulePath()  { return modulePath; }
	
	public static RuntimeBundle capture() throws IOException{
		var lease = SnapshotLease.open(Path.of(System.getProperty("java.io.tmpdir"), "jdbfs-fuzz-runtime-" + System.getProperty("user.name")));
		var dir   = lease.directory;
		try{
			var artifacts = new ArrayList<Artifact>();
			var seen      = new HashMap<Path, String>();
			
			var cp = snapshot(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path", "")), dir, artifacts, seen);
			var mp = snapshot(expandModulePath(System.getProperty("jdk.module.path", "")), dir, artifacts, seen);
			
			var flags = new ArrayList<String>();
			var args  = ManagementFactory.getRuntimeMXBean().getInputArguments();
			
			for(int i = 0; i<args.size(); i++){
				var arg = args.get(i);
				
				if(arg.equals("--patch-module") || arg.startsWith("--patch-module=")){
					if(arg.equals("--patch-module") && i + 1>=args.size()){
						throw new IOException("Missing value for " + arg);
					}
					var value = arg.equals("--patch-module")? args.get(++i) : arg.substring("--patch-module=".length());
					int eq    = value.indexOf('=');
					if(eq<1){
						throw new IOException("Invalid patch-module argument");
					}
					var entries = snapshot(value.substring(eq + 1), dir, artifacts, seen);
					flags.add("--patch-module=" + value.substring(0, eq) + "=" + String.join(":", entries));
				}else if(arg.startsWith("-D") && forwardProperty(arg)){
					flags.add(arg);
				}else if(List.of("--add-opens", "--add-exports", "--add-reads", "--add-modules", "--enable-native-access").contains(arg)){
					if(i + 1>=args.size()){
						throw new IOException("Missing value for " + arg);
					}
					flags.add(arg);
					flags.add(args.get(++i));
				}else if(Stream.of("--add-opens=", "--add-exports=", "--add-reads=", "--add-modules=", "--enable-native-access=", "-ea:").anyMatch(arg::startsWith) ||
				         List.of("--enable-preview", "-ea", "-esa").contains(arg)){
					flags.add(arg);
				}
			}
			
			var digest = digest();
			digest.update(
				(
					String.join(":", cp) + "\n" +
					String.join(":", mp) + "\n" +
					flags
				).getBytes(StandardCharsets.UTF_8)
			);
			String hash = HexFormat.of().formatHex(digest.digest());
			
			return new RuntimeBundle(artifacts, cp, mp, hash, flags, lease);
		}catch(Throwable e){
			lease.close();
			throw e;
		}
	}
	
	static boolean forwardProperty(String argument){
		// A newline would split into two arguments in the helper's line-based transports (Windows spec file).
		if(argument.indexOf('\n')>=0 || argument.indexOf('\r')>=0) return false;
		var property = argument.substring(2).split("=", 2)[0];
		// These paths belong to the master machine and not the Linux helper's filesystem
		return !property.startsWith("fuzz.remote.") &&
		       !Set.of("java.io.tmpdir", "java.library.path", "user.home", "user.dir").contains(property);
	}
	
	private static String expandModulePath(String path) throws IOException{
		var result = new ArrayList<String>();
		for(var entry : path.split(Pattern.quote(File.pathSeparator))){
			if(entry.isEmpty()) continue;
			
			var p = Path.of(entry);
			if(Files.isDirectory(p) && !Files.exists(p.resolve("module-info.class"))){
				try(var children = Files.list(p)){
					var modules = children.filter(child -> child.toString().endsWith(".jar") ||
					                                       Files.exists(child.resolve("module-info.class")))
					                      .sorted().toList();
					if(!modules.isEmpty()){
						modules.forEach(child -> result.add(child.toString()));
						continue;
					}
				}
			}
			result.add(entry);
		}
		return String.join(File.pathSeparator, result);
	}
	private record Stored(Path source, String name, Path target, boolean created){ }
	
	private static final int MAX_CONCURRENT_STORES = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 8)*2;
	
	private static List<String> snapshot(String path, Path dir, List<Artifact> artifacts, Map<Path, String> seen) throws IOException{
		var sources = new ArrayList<Path>();
		for(var entry : path.split(Pattern.quote(File.pathSeparator))){
			if(entry.isEmpty()) continue;
			var source = Path.of(entry).toAbsolutePath().normalize();
			if(!Files.exists(source)) throw new IOException("Missing runtime entry: " + source);
			sources.add(source);
		}
		
		var todo   = sources.stream().filter(s -> !seen.containsKey(s)).distinct().toList();
		var limit  = new Semaphore(MAX_CONCURRENT_STORES);
		var stored = new ArrayList<Stored>(todo.size());
		
		try(var exec = Executors.newVirtualThreadPerTaskExecutor()){
			var futures = new ArrayList<Future<Stored>>(todo.size());
			for(var s : todo){
				futures.add(exec.submit(() -> {
					limit.acquire();
					try{ return store(s, dir); }finally{ limit.release(); }
				}));
			}
			
			try{
				for(var f : futures){
					stored.add(f.get());
				}
			}catch(ExecutionException e){
				futures.forEach(f -> f.cancel(true));
				if(e.getCause() instanceof IOException io) throw io;
				if(e.getCause() instanceof RuntimeException re) throw re;
				throw new RuntimeException(e.getCause());
			}catch(InterruptedException e){
				futures.forEach(f -> f.cancel(true));
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while snapshotting runtime", e);
			}
		}
		
		var known = new HashSet<String>();
		artifacts.forEach(a -> known.add(a.name()));
		
		for(int i = 0; i<todo.size(); i++){
			var st = stored.get(i);
			seen.put(todo.get(i), st.name);
			if(st.created && known.add(st.name)){
				System.out.println("[fuzz init] Processed artifact: " + todo.get(i));
				artifacts.add(new Artifact(st.name, st.target));
			}
		}
		return sources.stream().map(seen::get).toList();
	}
	
	private static Stored store(Path source, Path dir) throws IOException{
		var hash = digest();
		// Automatic module name can be derived from the original filename
		var base = Files.isDirectory(source)? "classes.jar" : source.getFileName().toString();
		var temp = Files.createTempFile(dir, "artifact-", ".tmp");
		try{
			if(Files.isDirectory(source)){
				try(var out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), 1<<16), hash);
				    var jar = new JarOutputStream(out);
				    
				    var files = Files.walk(source)){
					for(var file : files.filter(Files::isRegularFile).sorted().toList()){
						var item = new JarEntry(source.relativize(file).toString().replace('\\', '/'));
						item.setTime(0);
						
						jar.putNextEntry(item);
						Files.copy(file, jar);
						jar.closeEntry();
					}
				}
			}else{
				try(var in = Files.newInputStream(source)){
					var buffer = new byte[1<<20];
					int n;
					while((n = in.read(buffer))>=0) hash.update(buffer, 0, n);
				}
				
				var name = HexFormat.of().formatHex(hash.digest()) + "/" + base;
				
				var target = dir.resolve(name);
				if(Files.exists(target)){
					return new Stored(source, name, target, false);
				}
				
				Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
				return place(source, name, target, temp);
			}
			
			var name = HexFormat.of().formatHex(hash.digest()) + "/" + base;
			
			var target = dir.resolve(name);
			if(Files.exists(target)){
				return new Stored(source, name, target, false);
			}
			
			return place(source, name, target, temp);
		}finally{
			Files.deleteIfExists(temp);
		}
	}
	
	private static Stored place(Path source, String name, Path target, Path temp) throws IOException{
		Files.createDirectories(target.getParent());
		try{
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
		}catch(FileAlreadyExistsException e){
			return new Stored(source, name, target, false);
		}
		return new Stored(source, name, target, true);
	}
	
	private static MessageDigest digest(){
		try{ return MessageDigest.getInstance("SHA-256"); }catch(NoSuchAlgorithmException e){
			throw new AssertionError(e);
		}
	}
	@Override
	public void close() throws IOException{ lease.close(); }
}
