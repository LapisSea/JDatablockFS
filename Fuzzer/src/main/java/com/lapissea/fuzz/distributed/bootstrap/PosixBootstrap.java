package com.lapissea.fuzz.distributed.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class PosixBootstrap implements HostBootstrap{
	
	static final PosixBootstrap INSTANCE = new PosixBootstrap();
	
	private PosixBootstrap(){ }
	
	@Override
	public Script prepare(String cache, String parent, UUID session, List<String> artifactNames){
		var script = new ShScript();
		
		script.exitOnError();
		script.createDirectories(literal(cache), literal(parent));
		script.resolveCanonicalPath("cache", literal(cache));
		script.resolveCanonicalPath("parent", literal(parent));
		script.printLines(variable("cache"), variable("parent"));
		script.lockCache(variable("cache"));
		script.createDirectories(variable("cache").child(".preparing"));
		script.touch(variable("cache").child(".preparing/" + session));
		script.append(cacheProbe(artifactNames));
		
		return new Script(script.toString(), List.of());
	}
	
	@Override
	public Script upload(String cache, UUID session, List<String> artifactNames){
		var script = new ShScript();
		
		script.exitOnError();
		script.lockCache(literal(cache));
		script.assign("staging", literal(cache + "/.upload-" + session));
		script.onExit("flock -w 25 9 && rm -rf -- \"$staging\"");
		script.createDirectories(variable("staging"));
		script.writeText(variable("staging").child(".fuzzer-managed"), RemoteWorkspace.MARKER);
		script.append("flock -u 9");
		script.extractTar(variable("staging"));
		script.append("flock -w 25 9");
		
		for(var name : artifactNames){
			var destination = cache + "/" + name;
			script.assign("artifact_dir", literal(destination.substring(0, destination.lastIndexOf('/'))));
			script.append(managedArtifactDirectory(true));
			script.move(variable("staging").child(name), literal(destination));
			script.touch(literal(destination));
		}
		
		return new Script(script.toString(), List.of());
	}
	
	@Override
	public Script failed(String cache, UUID session){
		var script = new ShScript();
		
		script.lockCache(literal(cache));
		script.removeFiles(literal(cache + "/.preparing/" + session));
		script.removeTree(literal(cache + "/.upload-" + session));
		
		return new Script(script.toString(), List.of());
	}
	
	@Override
	public CacheGate acquireCacheGate(Path cache, long timeoutMillis) throws IOException{
		var wait = timeoutMillis>0;
		var holder = new ProcessBuilder("flock", "--no-fork", "-E", "75", "-w",
		                                wait? Long.toString(Math.max(1, (timeoutMillis + 999)/1000)) : "0",
		                                "--", cache.resolve(".cleanup-lock").toString(), "sh", "-c", "printf x; exec cat >/dev/null")
			             .redirectError(ProcessBuilder.Redirect.INHERIT).start();
		
		var gate = new CacheGate.Lease(() -> {
			try{
				holder.getOutputStream().close();
				if(!holder.waitFor(5, TimeUnit.SECONDS)){
					holder.destroyForcibly();
				}
			}catch(InterruptedException e){
				holder.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted releasing fuzzer cache lock", e);
			}finally{
				if(holder.isAlive()){
					holder.destroyForcibly();
				}
				holder.getInputStream().close();
			}
		});
		try{
			int ready = holder.getInputStream().read();
			if(Thread.currentThread().isInterrupted()){
				throw new IOException("Interrupted acquiring fuzzer cache lock");
			}
			if(ready == 'x'){ return gate; }
			if(holder.waitFor() == 75 && !wait){
				gate.close();
				return null;
			}
			throw new IOException("Unable to acquire fuzzer cache lock: " + cache);
		}catch(InterruptedException e){
			holder.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted acquiring fuzzer cache lock", e);
		}catch(IOException|RuntimeException e){
			gate.close();
			throw e;
		}
	}
	
	@Override
	public String remoteInvocation(UUID session, List<String> executableAndArgs, PayloadTransfer transfer){
		// No command-line ceiling here, so `transfer` is never needed.
		return String.join(" ", executableAndArgs.stream().map(PosixBootstrap::quote).toList());
	}
	
	/**
	 * The cache lock on descriptor 9, held until the shell exits.
	 */
	static String cacheLock(String cache){
		var script = new ShScript();
		script.lockCache(literal(cache));
		return script.toString();
	}
	
	/**
	 * Prints the index (within {@code artifactNames}) of every artifact that is not a cached,
	 * real, regular file. A managed check runs first, so a foreign directory is an error rather
	 * than a cache miss.
	 */
	static String cacheProbe(List<String> artifactNames){
		var script = new ShScript();
		for(int i = 0; i<artifactNames.size(); i++){
			var name      = artifactNames.get(i);
			var directory = name.substring(0, name.lastIndexOf('/'));
			script.assign("artifact_dir", variable("cache").child(directory));
			script.append(managedArtifactDirectory(false));
			var file = variable("cache").child(name).expression();
			script.append("if test -f " + file + " && test ! -L " + file + "; then :; else printf '%s\\n' " + i + "; fi");
		}
		return script.toString();
	}
	
	private static String managedArtifactDirectory(boolean create){
		var createMissing = create?
		                    "else\n    mkdir -- \"$artifact_dir\"\n    printf '%s' " + quote(RemoteWorkspace.MARKER) + " > \"$artifact_dir/.fuzzer-managed\"\n" : "";
		return """
			if test -e "$artifact_dir" || test -L "$artifact_dir"; then
			    if test ! -L "$artifact_dir" && test -d "$artifact_dir" &&
			       test ! -L "$artifact_dir/.fuzzer-managed" && test -f "$artifact_dir/.fuzzer-managed" &&
			       [ "$(cat "$artifact_dir/.fuzzer-managed")" = %s ]; then
			        :
			    else
			        printf '%%s\\n' "Unmanaged fuzzer artifact directory: $artifact_dir" >&2
			        exit 1
			    fi
			%sfi
			""".formatted(quote(RemoteWorkspace.MARKER), createMissing);
	}
	
	static String quote(String value){ return "'" + value.replace("'", "'\\''") + "'"; }
	
	// Literal words and quoted shell variables stay distinct; user paths are never interpreted as shell code.
	private record Word(String expression){
		Word child(String name){ return new Word(expression + quote("/" + name)); }
	}
	private static Word literal(String value){ return new Word(quote(value)); }
	private static Word variable(String name){ return new Word("\"${" + name + "}\""); }
	
	private static final class ShScript{
		private final StringBuilder source = new StringBuilder();
		
		void append(String command)                      { source.append(command).append('\n'); }
		void exitOnError()                               { append("set -e"); }
		void assign(String name, Word value)             { append(name + "=" + value.expression()); }
		void onExit(String command)                      { append("trap " + quote(command) + " EXIT HUP INT TERM"); }
		void createDirectories(Word... paths)            { append("mkdir -p -- " + words(paths)); }
		void resolveCanonicalPath(String name, Word path){ append(name + "=$(cd " + path.expression() + " && pwd -P)"); }
		void printLines(Word... values)                  { append("printf '%s\\n' " + words(values)); }
		void touch(Word path)                            { append("touch -- " + path.expression()); }
		void removeFiles(Word path)                      { append("rm -f -- " + path.expression()); }
		void removeTree(Word path)                       { append("rm -rf -- " + path.expression()); }
		void move(Word source, Word target)              { append("mv -- " + source.expression() + " " + target.expression()); }
		void extractTar(Word directory)                  { append("tar -xf - -C " + directory.expression()); }
		void writeText(Word file, String value)          { append("printf '%s' " + quote(value) + " > " + file.expression()); }
		void lockCache(Word cache){
			append("exec 9>" + cache.child(".cleanup-lock").expression());
			append("flock -w 25 9 || exit 1");
		}
		private static String words(Word[] values){
			return Arrays.stream(values).map(Word::expression).collect(Collectors.joining(" "));
		}
		@Override
		public String toString(){ return source.toString(); }
	}
}
